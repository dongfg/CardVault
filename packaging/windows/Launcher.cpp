#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <shlobj.h>
#include <setupapi.h>
#include <bcrypt.h>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>
#include <stdexcept>
#include <algorithm>
#include <iterator>
#include "PayloadManifest.h"

namespace fs = std::filesystem;

struct Handle {
    HANDLE value;
    explicit Handle(HANDLE v) : value(v) {}
    ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
};

void Require(bool success, const char* message) {
    if (!success) throw std::runtime_error(message);
}

std::string Hash(const unsigned char* bytes, size_t length) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    Require(BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) == 0, "Cannot initialize SHA-256");
    unsigned char digest[32];
    const auto status = BCryptHash(algorithm, nullptr, 0, const_cast<PUCHAR>(bytes), static_cast<ULONG>(length), digest, sizeof(digest));
    BCryptCloseAlgorithmProvider(algorithm, 0);
    Require(status == 0, "Cannot calculate SHA-256");
    static constexpr char hex[] = "0123456789abcdef";
    std::string result;
    for (const auto b : digest) { result += hex[b >> 4]; result += hex[b & 15]; }
    return result;
}

bool Matches(const fs::path& path, const char* expected) {
    if (!fs::is_regular_file(path)) return false;
    const DWORD attributes = GetFileAttributesW(path.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_REPARSE_POINT)) return false;
    std::ifstream input(path, std::ios::binary);
    if (!input) return false;
    const std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(input)), {});
    return !input.bad() && Hash(bytes.data(), bytes.size()) == expected;
}

void Write(const fs::path& path, const unsigned char* data, size_t size) {
    const DWORD attributes = GetFileAttributesW(path.c_str());
    Require(attributes == INVALID_FILE_ATTRIBUTES || !(attributes & FILE_ATTRIBUTE_REPARSE_POINT), "Cache file must not be a link");
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(reinterpret_cast<const char*>(data), static_cast<std::streamsize>(size));
    Require(output.good(), "Cannot write the application cache");
}

struct Extraction { fs::path directory; };
UINT CALLBACK ExtractFile(PVOID context, UINT notification, UINT_PTR param1, UINT_PTR) {
    // No exceptions may cross the Win32 callback boundary.
    if (notification == SPFILENOTIFY_FILEINCABINET) {
        auto* info = reinterpret_cast<FILE_IN_CABINET_INFO_W*>(param1);
        const auto file = std::find_if(std::begin(PayloadFiles), std::end(PayloadFiles),
            [&](const auto& entry) { return wcscmp(entry.name, info->NameInCabinet) == 0; });
        if (file == std::end(PayloadFiles)) return FILEOP_ABORT;
        try {
            const auto target = static_cast<Extraction*>(context)->directory / file->name;
            if (Matches(target, file->sha256)) return FILEOP_SKIP;
            const DWORD attributes = GetFileAttributesW(target.c_str());
            if (attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_REPARSE_POINT)) return FILEOP_ABORT;
            if (wcscpy_s(info->FullTargetName, MAX_PATH, target.c_str()) != 0) return FILEOP_ABORT;
        } catch (...) { return FILEOP_ABORT; }
        return FILEOP_DOIT;
    }
    if (notification == SPFILENOTIFY_FILEEXTRACTED)
        return reinterpret_cast<FILEPATHS_W*>(param1)->Win32Error;
    if (notification == SPFILENOTIFY_NEEDNEWCABINET) return ERROR_CANCELLED;
    return NO_ERROR;
}

fs::path ExecutablePath() {
    std::vector<wchar_t> path(32768);
    const auto size = GetModuleFileNameW(nullptr, path.data(), static_cast<DWORD>(path.size()));
    Require(size > 0 && size < path.size(), "Cannot locate the launcher");
    return std::wstring(path.data(), size);
}

int WINAPI wWinMain(HINSTANCE module, HINSTANCE, PWSTR arguments, int) {
    try {
        const auto resource = FindResourceW(module, MAKEINTRESOURCEW(101), RT_RCDATA);
        Require(resource != nullptr, "Application payload is missing");
        const auto loaded = LoadResource(module, resource);
        const auto data = static_cast<const unsigned char*>(LockResource(loaded));
        const auto size = SizeofResource(module, resource);
        Require(data && size > 0, "Application payload is empty");
        const auto hash = Hash(data, size);
        const std::wstring key(hash.begin(), hash.end());
        PWSTR local = nullptr;
        Require(SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_CREATE, nullptr, &local)), "Cannot locate LocalAppData");
        fs::path cache(local); CoTaskMemFree(local);
        for (const auto& part : {std::wstring(L"CardVault"), std::wstring(L"cache"), key}) {
            cache /= part;
            fs::create_directory(cache);
            Require(!(GetFileAttributesW(cache.c_str()) & FILE_ATTRIBUTE_REPARSE_POINT), "Cache directory must not be a link");
        }
        Handle mutex(CreateMutexW(nullptr, FALSE, (L"Local\\CardVault-payload-" + key).c_str()));
        Require(mutex.value != nullptr, "Cannot create cache lock");
        const auto wait = WaitForSingleObject(mutex.value, 120000);
        Require(wait == WAIT_OBJECT_0 || wait == WAIT_ABANDONED, "Timed out waiting for cache extraction");
        struct Unlock { HANDLE mutex; ~Unlock() { if (mutex) ReleaseMutex(mutex); } } unlock{mutex.value};
        const auto valid = [&] {
            return std::all_of(std::begin(PayloadFiles), std::end(PayloadFiles), [&](const auto& file) { return Matches(cache / file.name, file.sha256); });
        };
        if (!valid()) {
            const auto cabinet = cache / L"payload.cab";
            Write(cabinet, data, size);
            Extraction extraction{cache};
            Require(SetupIterateCabinetW(cabinet.c_str(), 0, ExtractFile, &extraction) != FALSE, "Cannot extract the application payload");
            Require(valid(), "Application payload verification failed");
            fs::remove(cabinet);
        }
        ReleaseMutex(mutex.value); unlock.mutex = nullptr;
        // CI can validate extraction and hashes without opening a desktop window.
        if (wcscmp(arguments, L"--launcher-check") == 0) return 0;
        const auto outer = ExecutablePath();
        Require(SetEnvironmentVariableW(L"CARDVAULT_LAUNCHER_PATH", outer.c_str()) != FALSE, "Cannot set application location");
        const auto child = cache / L"CardVault.exe";
        auto command = L"\"" + child.wstring() + L"\" " + arguments;
        STARTUPINFOW startup{}; startup.cb = sizeof(startup);
        PROCESS_INFORMATION process{};
        Require(CreateProcessW(child.c_str(), command.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr, &startup, &process) != FALSE, "Cannot start CardVault");
        Handle childProcess(process.hProcess), childThread(process.hThread);
        Require(WaitForSingleObject(childProcess.value, INFINITE) == WAIT_OBJECT_0, "Cannot wait for CardVault");
        DWORD exitCode = 1;
        GetExitCodeProcess(childProcess.value, &exitCode);
        return static_cast<int>(exitCode);
    } catch (const std::exception& error) {
        MessageBoxA(nullptr, error.what(), "CardVault", MB_OK | MB_ICONERROR);
        return 1;
    }
}
