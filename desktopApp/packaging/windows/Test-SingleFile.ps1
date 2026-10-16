param(
    [string]$Executable = (Join-Path $PSScriptRoot '..\..\build\distributions\CardVault-1.0.0-windows-x64-single.exe')
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $Executable).Path
$buildRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\build'))
$testRoot = Join-Path $buildRoot ('single-file-tests\' + [guid]::NewGuid().ToString('N'))
$copyDirectory = Join-Path $testRoot 'portable 中文 folder'
New-Item -ItemType Directory -Path $copyDirectory -Force | Out-Null
$copy = Join-Path $copyDirectory 'CardVault test.exe'
Copy-Item -LiteralPath $source -Destination $copy

function Start-Prepare([string]$Cache) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $copy
    $start.Arguments = '--launcher-prepare-only'
    $start.WorkingDirectory = $testRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.EnvironmentVariables['CARDVAULT_LAUNCHER_CACHE'] = $Cache
    return [Diagnostics.Process]::Start($start)
}

function Wait-Prepare($Process) {
    try {
        if (-not $Process.WaitForExit(30000)) {
            $Process.Kill()
            throw 'Launcher preparation timed out'
        }
        if ($Process.ExitCode -ne 0) { throw "Launcher exited with code $($Process.ExitCode)" }
    } finally { $Process.Dispose() }
}

function Runtime-Directory([string]$Cache) {
    $versions = @(Get-ChildItem -LiteralPath $Cache -Directory | Where-Object Name -Match '^[a-f0-9]{64}$')
    if ($versions.Count -ne 1) { throw "Expected one complete runtime, found $($versions.Count)" }
    return $versions[0].FullName
}

$cache = Join-Path $testRoot 'cache with spaces'
$sentinel = Join-Path $copyDirectory 'cardvault.db'
Set-Content -LiteralPath $sentinel -Value 'persistent card data fixture' -Encoding utf8

Wait-Prepare (Start-Prepare $cache)
$runtime = Runtime-Directory $cache
$marker = Join-Path $runtime '.complete'
if (-not (Test-Path -LiteralPath (Join-Path $runtime 'CardVault\runtime\bin\server\jvm.dll'))) { throw 'Bundled JVM missing' }
$timestamp = (Get-Item -LiteralPath $marker).LastWriteTimeUtc.Ticks
Write-Output 'PASS: cold extraction, bundled JVM, Unicode and spaces in path'

Wait-Prepare (Start-Prepare $cache)
if ((Get-Item -LiteralPath $marker).LastWriteTimeUtc.Ticks -ne $timestamp) { throw 'Warm launch unexpectedly extracted again' }
if (-not ((Get-Content -LiteralPath (Join-Path $cache 'launcher.log') -Raw) -match 'Reused runtime')) { throw 'Cache was not reused' }
Write-Output 'PASS: warm launch reuses cache without rewriting completion marker'

$concurrentCache = Join-Path $testRoot 'concurrent-cache'
$processes = @(1..3 | ForEach-Object { Start-Prepare $concurrentCache })
foreach ($process in $processes) { Wait-Prepare $process }
$null = Runtime-Directory $concurrentCache
$log = Get-Content -LiteralPath (Join-Path $concurrentCache 'launcher.log') -Raw
if ([regex]::Matches($log, 'Extracted runtime').Count -ne 1) { throw 'Concurrent launches extracted more than once' }
Write-Output 'PASS: three simultaneous cold starts extract exactly once'

# Only mutate a generated test fixture, never an actual user cache or card directory.
if (-not $marker.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test marker outside build directory' }
Remove-Item -LiteralPath $marker
Wait-Prepare (Start-Prepare $cache)
if (-not (Test-Path -LiteralPath $marker)) { throw 'Incomplete runtime was not repaired' }
if (-not ((Get-Content -LiteralPath $sentinel -Raw) -match 'persistent card data fixture')) { throw 'Card data was modified' }
Write-Output 'PASS: incomplete cache is repaired and external data remains intact'
Write-Output "Test artifacts: $testRoot"
