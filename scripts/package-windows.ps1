param(
    [Parameter(Mandatory)][string]$PublishDirectory,
    [Parameter(Mandatory)][string]$OutputFile
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
Compress-Archive -Path (Join-Path $published '*') -DestinationPath $output
