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
# Native NuGet dependencies can copy their own PDBs despite DebugSymbols=false.
# Keep subdirectories and runtime dependencies, but never ship debugging symbols.
$archive = [System.IO.Compression.ZipFile]::Open($output, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $published -Recurse -File) {
        if ($file.Extension -in @('.pdb', '.dbg', '.dwarf')) { continue }
        $entryName = [System.IO.Path]::GetRelativePath($published, $file.FullName).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, $file.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
} finally {
    $archive.Dispose()
}
