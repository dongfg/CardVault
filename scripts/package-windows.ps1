param(
    [Parameter(Mandatory)][string]$PublishDirectory,
    [Parameter(Mandatory)][string]$OutputFile,
    [Parameter(Mandatory)][ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$Version
)

$ErrorActionPreference = 'Stop'
$published = (Resolve-Path -LiteralPath $PublishDirectory).Path
if (-not (Test-Path -LiteralPath (Join-Path $published 'CardVault.exe') -PathType Leaf)) {
    throw 'Native AOT executable is missing'
}
foreach ($library in @('libSkiaSharp.dll', 'libHarfBuzzSharp.dll', 'av_libglesv2.dll', 'e_sqlite3.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $published $library) -PathType Leaf)) {
        throw "Required native library is missing: $library"
    }
}
if (Test-Path -LiteralPath (Join-Path $published 'CardVault.dll')) {
    throw 'Expected a Native AOT publish, found managed CardVault.dll'
}
$output = [System.IO.Path]::GetFullPath($OutputFile)
if (Test-Path -LiteralPath $output) { throw 'Package already exists; refusing to overwrite it' }
New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../LICENSE') -Destination (Join-Path $published 'LICENSE.txt')
# Load the installed C++ toolchain into this PowerShell process. Nothing is downloaded.
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) { throw 'Visual Studio C++ Build Tools are required' }
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'Visual Studio x64 C++ toolchain was not found' }
Import-Module (Join-Path $installation 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $installation -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64' | Out-Null

$work = Join-Path (Split-Path -Parent $output) ('launcher-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$files = @(Get-ChildItem -LiteralPath $published -Recurse -File | Where-Object { $_.Extension -notin @('.pdb','.dbg','.dwarf') })
foreach ($file in $files) {
    if ($file.DirectoryName -ne $published) { throw 'Unexpected nested publish files; update the launcher payload layout before packaging' }
    if ($file.Name -match '["\r\n]') { throw 'Unsupported payload filename' }
}
function CString([string]$Value) { $Value.Replace('\', '\\').Replace('"', '\"') }
$manifest = @('struct PayloadFile { const wchar_t* name; const char* sha256; };', 'static constexpr PayloadFile PayloadFiles[] = {')
$ddf = @('.OPTION EXPLICIT', '.Set CabinetNameTemplate=payload.cab', ('.Set DiskDirectoryTemplate="' + $work + '"'),
    '.Set Cabinet=ON', '.Set Compress=ON', '.Set CompressionType=LZX', '.Set CompressionMemory=21', '.Set MaxDiskSize=0',
    ('.Set RptFileName="' + (Join-Path $work 'payload.rpt') + '"'), ('.Set InfFileName="' + (Join-Path $work 'payload.inf') + '"'))
foreach ($file in $files) {
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest += '    {L"' + (CString $file.Name) + '", "' + $hash + '"},'
    $ddf += '"' + $file.FullName + '" "' + $file.Name + '"'
}
$manifest += '};'
$manifest | Set-Content -LiteralPath (Join-Path $work 'PayloadManifest.h') -Encoding utf8
# makecab uses the active Windows code page for directive paths.
[System.IO.File]::WriteAllLines((Join-Path $work 'payload.ddf'), [string[]]$ddf, [System.Text.Encoding]::GetEncoding([System.Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage))
& makecab.exe /F (Join-Path $work 'payload.ddf')
if ($LASTEXITCODE -ne 0) { throw 'Cabinet compression failed' }
$cabinet = Join-Path $work 'payload.cab'
if (-not (Test-Path -LiteralPath $cabinet)) { throw 'Cabinet payload is missing' }
$resource = @'
#include <windows.h>
101 RCDATA "{0}"
1 ICON "{1}"
1 RT_MANIFEST "{2}"
1 VERSIONINFO
FILEVERSION {3},0
PRODUCTVERSION {3},0
FILEOS VOS_NT_WINDOWS32
FILETYPE VFT_APP
BEGIN
  BLOCK "StringFileInfo" BEGIN
    BLOCK "040904b0" BEGIN
      VALUE "FileDescription", "CardVault"
      VALUE "ProductName", "CardVault"
      VALUE "FileVersion", "{4}"
      VALUE "ProductVersion", "{4}"
    END
  END
  BLOCK "VarFileInfo" BEGIN
    VALUE "Translation", 0x409, 1200
  END
END
'@ -f (CString $cabinet), (CString (Join-Path $PSScriptRoot '../src/CardVault/Assets/cardvault.ico')), (CString (Join-Path $PSScriptRoot '../packaging/windows/launcher.manifest')), $Version.Replace('.', ','), $Version
$resourcePath = Join-Path $work 'launcher.rc'
$resource | Set-Content -LiteralPath $resourcePath -Encoding unicode
$compiledResource = Join-Path $work 'launcher.res'
& rc.exe /nologo "/fo$compiledResource" $resourcePath
if ($LASTEXITCODE -ne 0) { throw 'Launcher resource compilation failed' }
$compilerArguments = @('/nologo', '/std:c++17', '/O2', '/MT', '/EHsc', '/W4', '/utf-8', '/DUNICODE', '/D_UNICODE', '/D_WIN32_WINNT=0x0A00',
    "/I$work", "/Fo$(Join-Path $work 'Launcher.obj')", "/Fe$output",
    (Join-Path $PSScriptRoot '../packaging/windows/Launcher.cpp'), $compiledResource,
    '/link', '/SUBSYSTEM:WINDOWS', '/DYNAMICBASE', '/NXCOMPAT', '/OPT:REF', '/OPT:ICF', 'bcrypt.lib', 'setupapi.lib', 'shell32.lib', 'ole32.lib', 'user32.lib')
& cl.exe @compilerArguments
if ($LASTEXITCODE -ne 0) { throw 'Native launcher compilation failed' }
# Fail CI before upload if extraction or hash verification is broken.
$check = Start-Process -FilePath $output -ArgumentList '--launcher-check' -PassThru
if (-not $check.WaitForExit(120000)) { $check.Kill(); throw 'Launcher self-check timed out' }
if ($check.ExitCode -ne 0) { throw 'Launcher self-check failed' }
$probeDirectory = Join-Path $work '路径 with spaces'
New-Item -ItemType Directory -Path $probeDirectory | Out-Null
$probeExecutable = Join-Path $probeDirectory 'CardVault renamed.exe'
Copy-Item -LiteralPath $output -Destination $probeExecutable
$warmCheck = Start-Process -FilePath $probeExecutable -ArgumentList '--launcher-check' -PassThru
if (-not $warmCheck.WaitForExit(120000)) { $warmCheck.Kill(); throw 'Renamed launcher self-check timed out' }
if ($warmCheck.ExitCode -ne 0) { throw 'Warm cache / Unicode path launcher self-check failed' }
