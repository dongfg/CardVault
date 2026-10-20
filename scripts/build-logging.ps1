Set-StrictMode -Version Latest

function Write-BuildLog {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "[$([DateTime]::UtcNow.ToString('HH:mm:ss')) UTC] $Message"
}

function Invoke-BuildProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [Parameter(Mandatory)][string]$Label,
        [ValidateRange(1,3600)][int]$TimeoutSeconds = 120
    )
    $ErrorActionPreference = 'Stop'
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $ArgumentList) { $start.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    $started = $false
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $nextHeartbeat = 10
    Write-BuildLog "START $Label (timeout ${TimeoutSeconds}s)"
    try {
        if (-not $process.Start()) { throw "Could not start $Label" }
        $started = $true
        $streams = @(
            [pscustomobject]@{ Reader = $process.StandardOutput; Pending = $process.StandardOutput.ReadLineAsync() },
            [pscustomobject]@{ Reader = $process.StandardError; Pending = $process.StandardError.ReadLineAsync() }
        )
        while ($true) {
            foreach ($stream in $streams) {
                while ($null -ne $stream.Pending -and $stream.Pending.IsCompleted) {
                    $line = $stream.Pending.GetAwaiter().GetResult()
                    if ($null -eq $line) { $stream.Pending = $null; break }
                    Write-Host "[$Label] $line"
                    $stream.Pending = $stream.Reader.ReadLineAsync()
                }
            }
            if ($process.HasExited -and $null -eq $streams[0].Pending -and $null -eq $streams[1].Pending) { break }
            if ($clock.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
                if (-not $process.HasExited) { $process.Kill($true) }
                throw "$Label timed out after ${TimeoutSeconds}s; process tree terminated"
            }
            if ($clock.Elapsed.TotalSeconds -ge $nextHeartbeat) {
                Write-BuildLog "RUNNING $Label ($([int]$clock.Elapsed.TotalSeconds)s elapsed)"
                $nextHeartbeat += 10
            }
            Start-Sleep -Milliseconds 100
        }
        $exitCode = $process.ExitCode
        Write-BuildLog "END $Label ($([Math]::Round($clock.Elapsed.TotalSeconds,1))s, exit $exitCode)"
        if ($exitCode -ne 0) { throw "$Label failed with exit code $exitCode" }
    } finally {
        try { if ($started -and -not $process.HasExited) { $process.Kill($true) } } finally { $process.Dispose() }
    }
}

function Get-BuildDownload {
    param([Parameter(Mandatory)][uri]$Uri, [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][string]$Label)
    $curl = (Get-Command curl.exe -ErrorAction Stop).Source
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        Write-BuildLog "DOWNLOAD $Label attempt $attempt/2: $($Uri.AbsoluteUri)"
        try {
            Invoke-BuildProcess -FilePath $curl -Label "Download $Label attempt $attempt" -TimeoutSeconds 50 -ArgumentList @(
                '--fail', '--location', '--silent', '--show-error', '--connect-timeout', '10', '--max-time', '40',
                '--output', $Destination,
                '--write-out', "HTTP=%{http_code} peer=%{remote_ip} bytes=%{size_download} DNS=%{time_namelookup}s TCP=%{time_connect}s TLS=%{time_appconnect}s first-byte=%{time_starttransfer}s total=%{time_total}s\n",
                $Uri.AbsoluteUri)
            if (-not (Test-Path -LiteralPath $Destination -PathType Leaf) -or (Get-Item -LiteralPath $Destination).Length -eq 0) {
                throw "$Label response body is empty"
            }
            Write-BuildLog "DOWNLOADED $Label ($((Get-Item -LiteralPath $Destination).Length) bytes)"
            return
        } catch {
            Write-BuildLog "DOWNLOAD FAILED $Label attempt ${attempt}: $($_.Exception.Message)"
            if ($attempt -eq 2) { throw }
            Start-Sleep -Seconds 2
        }
    }
}
