param(
    [Parameter(Mandatory)][string]$PublishDirectory,
    [Parameter(Mandatory)][string]$OutputFile,
    [Parameter(Mandatory)][ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$Version,
    [uri]$SigningCertificateUrl = 'https://cdn.dongfg.com/certs/codesign.pfx',
    [uri]$RootCertificateUrl = 'https://cdn.dongfg.com/certs/root-ca.cer'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'build-logging.ps1')
$packageClock = [System.Diagnostics.Stopwatch]::StartNew()
Write-BuildLog "Windows packaging started; version=$Version"
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
Write-BuildLog 'Locate Visual Studio C++ toolchain'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) { throw 'Visual Studio C++ Build Tools are required' }
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'Visual Studio x64 C++ toolchain was not found' }
Write-BuildLog "Initialize Visual Studio developer shell: $installation"
Import-Module (Join-Path $installation 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $installation -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64' | Out-Null
Write-BuildLog 'Visual Studio developer shell ready'

. (Join-Path $PSScriptRoot 'windows-signing.ps1')
$signing = $null
try {
    # Local signing uses an already trusted root; only CI temporarily adds trust.
    $signing = New-WindowsSigningContext -CertificateUrl $SigningCertificateUrl -RootCertificateUrl $RootCertificateUrl -TrustRoot:($env:GITHUB_ACTIONS -eq 'true')
    $payloadBinaries = @(Get-ChildItem -LiteralPath $published -File | Where-Object { $_.Extension -in @('.exe','.dll') })
    Write-BuildLog "Sign $($payloadBinaries.Count) payload binaries"
    Invoke-WindowsCodeSigning -Context $signing -FilePath $payloadBinaries.FullName

$work = Join-Path (Split-Path -Parent $output) ('launcher-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$files = @(Get-ChildItem -LiteralPath $published -Recurse -File | Where-Object { $_.Extension -notin @('.pdb','.dbg','.dwarf') })
Write-BuildLog "Create payload manifest for $($files.Count) files"
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
Invoke-BuildProcess -FilePath (Get-Command makecab.exe).Source -Label 'LZX cabinet compression' -TimeoutSeconds 300 -ArgumentList @('/F', (Join-Path $work 'payload.ddf'))
$cabinet = Join-Path $work 'payload.cab'
if (-not (Test-Path -LiteralPath $cabinet)) { throw 'Cabinet payload is missing' }
Write-BuildLog "Cabinet ready: $((Get-Item -LiteralPath $cabinet).Length) bytes"
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
Invoke-BuildProcess -FilePath (Get-Command rc.exe).Source -Label 'Launcher resource compilation' -TimeoutSeconds 60 -ArgumentList @('/nologo', "/fo$compiledResource", $resourcePath)
$compilerArguments = @('/nologo', '/std:c++17', '/O2', '/MT', '/EHsc', '/W4', '/utf-8', '/DUNICODE', '/D_UNICODE', '/D_WIN32_WINNT=0x0A00',
    "/I$work", "/Fo$(Join-Path $work 'Launcher.obj')", "/Fe$output",
    (Join-Path $PSScriptRoot '../packaging/windows/Launcher.cpp'), $compiledResource,
    '/link', '/SUBSYSTEM:WINDOWS', '/DYNAMICBASE', '/NXCOMPAT', '/OPT:REF', '/OPT:ICF', 'bcrypt.lib', 'setupapi.lib', 'shell32.lib', 'ole32.lib', 'user32.lib')
Invoke-BuildProcess -FilePath (Get-Command cl.exe).Source -Label 'Native launcher compilation' -TimeoutSeconds 180 -ArgumentList $compilerArguments
Write-BuildLog 'Sign outer single-file launcher'
Invoke-WindowsCodeSigning -Context $signing -FilePath $output
# Fail CI before upload if extraction or hash verification is broken.
Invoke-BuildProcess -FilePath $output -Label 'Launcher cold-cache extraction check' -TimeoutSeconds 120 -ArgumentList @('--launcher-check')
$probeDirectory = Join-Path $work '路径 with spaces'
New-Item -ItemType Directory -Path $probeDirectory | Out-Null
$probeExecutable = Join-Path $probeDirectory 'CardVault renamed.exe'
Copy-Item -LiteralPath $output -Destination $probeExecutable
Invoke-BuildProcess -FilePath $probeExecutable -Label 'Launcher warm-cache Unicode path check' -TimeoutSeconds 120 -ArgumentList @('--launcher-check')
Write-BuildLog "Windows packaging complete: $output ($([Math]::Round($packageClock.Elapsed.TotalSeconds,1))s total)"
} catch {
    $failure = $_.Exception.Message
    Write-BuildLog "WINDOWS PACKAGING FAILED: $failure"
    $annotation = $failure.Replace('%','%25').Replace("`r",'%0D').Replace("`n",'%0A')
    Write-Host "::error title=Windows packaging failed::$annotation"
    throw
} finally {
    if ($signing) { Close-WindowsSigningContext -Context $signing }
}
