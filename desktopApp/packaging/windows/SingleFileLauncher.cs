// Compiled with the .NET Framework compiler supplied with Windows 10/11.
// The application remains Kotlin/Compose; this host only extracts and starts it.
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;

internal static class SingleFileLauncher
{
    private static readonly Assembly Self = Assembly.GetExecutingAssembly();
    private static readonly string[] RequiredFiles = {
        "CardVault.exe", "app/CardVault.cfg", "runtime/bin/server/jvm.dll"
    };
    private static string logPath;

    [STAThread]
    private static int Main(string[] args)
    {
        bool prepareOnly = args.Contains("--launcher-prepare-only");
        try
        {
            // Modern .NET Framework supports extended-length paths. The host has no sidecar .config.
            AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
            AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
            string launcherDirectory = Path.GetDirectoryName(Self.Location);
            string cacheRoot = Path.GetFullPath(Environment.GetEnvironmentVariable("CARDVAULT_LAUNCHER_CACHE") ??
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CardVault", "launcher", "cache"));
            Directory.CreateDirectory(cacheRoot);
            logPath = Path.Combine(cacheRoot, "launcher.log");
            string hash;
            using (var reader = new StreamReader(Self.GetManifestResourceStream("CardVault.PayloadHash")))
                hash = reader.ReadToEnd().Trim();
            if (hash.Length != 64 || hash.Any(c => !Uri.IsHexDigit(c)))
                throw new InvalidDataException("Invalid payload identifier.");

            string runtime = EnsureRuntime(cacheRoot, hash);
            if (prepareOnly) return 0;

            // Persistent card data never enters a replaceable runtime directory.
            string dataDirectory = Path.GetFullPath(Environment.GetEnvironmentVariable("CARDVAULT_DATA_DIR") ??
                Path.Combine(launcherDirectory, args.Contains("--demo") ? "demo-data" : "data"));
            Directory.CreateDirectory(dataDirectory);
            var start = new ProcessStartInfo(Path.Combine(runtime, "CardVault", "CardVault.exe")) {
                WorkingDirectory = launcherDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                Arguments = string.Join(" ", args.Select(QuoteArgument))
            };
            start.EnvironmentVariables["CARDVAULT_DATA_DIR"] = dataDirectory;
            var child = Process.Start(start);
            if (child == null) throw new IOException("Could not start CardVault.");
            Log("Started CardVault, PID " + child.Id);
            child.Dispose();
            return 0;
        }
        catch (Exception error)
        {
            Log(error.ToString());
            if (!prepareOnly)
                MessageBox.Show("CardVault 无法启动。请确认程序所在目录和缓存目录可写。\n" +
                    "如缓存损坏，请关闭 CardVault 后清理缓存并重试。\n\n日志：" + (logPath ?? "无法创建日志") +
                    "\n\n" + error.Message, "CardVault", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static string EnsureRuntime(string cacheRoot, string hash)
    {
        string destination = Path.Combine(cacheRoot, hash);
        // All concurrent launches of this payload share a lock, including a cold start.
        using (var mutex = new Mutex(false, @"Local\CardVault.Extract." + hash))
        {
            bool ownsLock = false;
            try
            {
                try { ownsLock = mutex.WaitOne(TimeSpan.FromMinutes(5)); }
                catch (AbandonedMutexException) { ownsLock = true; }
                if (!ownsLock) throw new TimeoutException("Another CardVault launch is still extracting files.");
                if (IsReady(destination, hash)) { Log("Reused runtime " + hash); return destination; }

                string staging = Path.Combine(cacheRoot, ".staging-" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(staging);
                try
                {
                    using (var payload = Self.GetManifestResourceStream("CardVault.Payload"))
                    {
                        if (payload == null) throw new InvalidDataException("Missing application payload.");
                        using (var sha = SHA256.Create())
                        {
                            string actual = BitConverter.ToString(sha.ComputeHash(payload)).Replace("-", "").ToLowerInvariant();
                            if (!string.Equals(actual, hash, StringComparison.OrdinalIgnoreCase))
                                throw new InvalidDataException("The application payload is damaged. Please download a new copy.");
                        }
                        payload.Position = 0;
                        using (var zip = new ZipArchive(payload, ZipArchiveMode.Read, true)) Extract(zip, staging);
                    }
                    foreach (string relative in RequiredFiles)
                        if (!File.Exists(Path.Combine(staging, "CardVault", relative.Replace('/', Path.DirectorySeparatorChar))))
                            throw new InvalidDataException("Incomplete application payload: " + relative);
                    File.WriteAllText(Path.Combine(staging, ".complete"), hash, Encoding.ASCII);

                    // Quarantine an interrupted/broken cache instead of deleting a possibly running app.
                    if (Directory.Exists(destination)) Directory.Move(destination, destination + ".invalid-" + Guid.NewGuid().ToString("N"));
                    Directory.Move(staging, destination);
                    Log("Extracted runtime " + hash);
                }
                finally
                {
                    // This path is generated above, under our exact cache root. Never touch card data.
                    if (Directory.Exists(staging) && Path.GetFullPath(staging).StartsWith(
                        cacheRoot.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar + ".staging-", StringComparison.OrdinalIgnoreCase))
                        Directory.Delete(staging, true);
                }
                return destination;
            }
            finally { if (ownsLock) mutex.ReleaseMutex(); }
        }
    }

    private static bool IsReady(string destination, string hash)
    {
        string marker = Path.Combine(destination, ".complete");
        return File.Exists(marker) && File.ReadAllText(marker).Trim() == hash && RequiredFiles.All(
            path => File.Exists(Path.Combine(destination, "CardVault", path.Replace('/', Path.DirectorySeparatorChar))));
    }

    private static void Extract(ZipArchive zip, string staging)
    {
        string root = Path.GetFullPath(staging).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        foreach (var entry in zip.Entries)
        {
            if (!entry.FullName.StartsWith("CardVault/", StringComparison.Ordinal))
                throw new InvalidDataException("Unexpected archive entry.");
            string output = Path.GetFullPath(Path.Combine(staging, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
            if (!output.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Archive entry escapes the extraction directory.");
            if (entry.FullName.EndsWith("/")) { Directory.CreateDirectory(output); continue; }
            Directory.CreateDirectory(Path.GetDirectoryName(output));
            using (var input = entry.Open())
            using (var file = new FileStream(output, FileMode.CreateNew, FileAccess.Write)) input.CopyTo(file);
        }
    }

    // CommandLineToArgvW-compatible quoting; filenames with spaces and trailing slashes survive.
    private static string QuoteArgument(string argument)
    {
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in argument)
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') { result.Append('\\', slashes * 2 + 1).Append('"'); slashes = 0; continue; }
            result.Append('\\', slashes).Append(c); slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }

    private static void Log(string text)
    {
        try { if (logPath != null) File.AppendAllText(logPath, DateTime.UtcNow.ToString("O") + " " + text + Environment.NewLine); }
        catch { /* Logging must never prevent launching or displaying the real error. */ }
    }
}
