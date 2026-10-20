# Shared by Windows packaging. Dot-sourcing this file does not download or install anything.
Set-StrictMode -Version Latest

function New-WindowsSigningContext {
    param(
        [uri]$CertificateUrl = 'https://cdn.dongfg.com/certs/codesign.pfx',
        [uri]$RootCertificateUrl = 'https://cdn.dongfg.com/certs/root-ca.cer',
        [string]$RootSha256 = '5E196B56432C816D7D53638B0BF5EF87E50B3211C5CD325DF4E266439D9AFB96',
        [uri]$TimestampUrl = 'http://timestamp.digicert.com',
        [switch]$TrustRoot
    )
    $ErrorActionPreference = 'Stop'
    if ($CertificateUrl.Scheme -ne 'https' -or $RootCertificateUrl.Scheme -ne 'https') {
        throw 'Certificate download URLs must use HTTPS'
    }
    $command = Get-Command signtool.exe -ErrorAction SilentlyContinue
    $signTool = if ($command) { $command.Source } else { $null }
    if (-not $signTool) {
        $sdk = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/bin'
        $versions = @(Get-ChildItem -LiteralPath $sdk -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
            Sort-Object { [version]$_.Name } -Descending)
        foreach ($version in $versions) {
            $candidate = Join-Path $version.FullName 'x64/signtool.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $signTool = $candidate; break }
        }
    }
    if (-not $signTool) { throw 'Windows SDK x64 signtool.exe was not found' }
    $context = [pscustomobject]@{
        Directory = Join-Path ([System.IO.Path]::GetTempPath()) ('cardvault-signing-' + [Guid]::NewGuid().ToString('N'))
        PfxPath = ''; RootPath = ''; RootCertificate = $null; RootImported = $false
        SignerThumbprint = ''; SignTool = $signTool; TimestampUrl = $TimestampUrl.AbsoluteUri
    }
    $signer = $null
    $chain = $null
    try {
        New-Item -ItemType Directory -Path $context.Directory | Out-Null
        $context.PfxPath = Join-Path $context.Directory 'codesign.pfx'
        $context.RootPath = Join-Path $context.Directory 'root-ca.cer'
        Invoke-WebRequest -Uri $RootCertificateUrl -OutFile $context.RootPath -MaximumRetryCount 3
        Invoke-WebRequest -Uri $CertificateUrl -OutFile $context.PfxPath -MaximumRetryCount 3
        # Constructors work in the PowerShell/.NET version shipped on Windows runners.
        $context.RootCertificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($context.RootPath)
        $actualRootHash = $context.RootCertificate.GetCertHashString([System.Security.Cryptography.HashAlgorithmName]::SHA256)
        if ($actualRootHash -ne ($RootSha256 -replace '[:\s-]', '')) { throw 'Downloaded root certificate fingerprint does not match' }
        $signer = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
            $context.PfxPath, [string]::Empty, [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
        if (-not $signer.HasPrivateKey) { throw 'Signing PFX does not contain a private key' }
        $chain = [System.Security.Cryptography.X509Certificates.X509Chain]::new()
        $chain.ChainPolicy.TrustMode = [System.Security.Cryptography.X509Certificates.X509ChainTrustMode]::CustomRootTrust
        $chain.ChainPolicy.CustomTrustStore.Add($context.RootCertificate) | Out-Null
        # This personal CA does not publish a CRL/OCSP endpoint. Signature verification still checks its chain and validity.
        $chain.ChainPolicy.RevocationMode = [System.Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck
        $chain.ChainPolicy.ApplicationPolicy.Add([System.Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.3')) | Out-Null
        if (-not $chain.Build($signer)) { throw 'Signing certificate chain, validity or code-signing purpose is invalid' }
        $context.SignerThumbprint = $signer.Thumbprint
        if ($TrustRoot) {
            # Trust is added only on the disposable CI runner and removed on completion.
            $store = [System.Security.Cryptography.X509Certificates.X509Store]::new('Root', 'CurrentUser')
            try {
                $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
                $existing = $store.Certificates.Find([System.Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,
                    $context.RootCertificate.Thumbprint, $false)
                if ($existing.Count -eq 0) { $store.Add($context.RootCertificate); $context.RootImported = $true }
            } finally { $store.Dispose() }
        }
        Write-Host "Signing identity: $($signer.Subject)"
        return $context
    } catch {
        Close-WindowsSigningContext -Context $context
        throw
    } finally {
        if ($chain) { $chain.Dispose() }
        if ($signer) { $signer.Dispose() }
    }
}

function Invoke-WindowsCodeSigning {
    param([Parameter(Mandatory)]$Context, [Parameter(Mandatory)][string[]]$FilePath)
    $ErrorActionPreference = 'Stop'
    foreach ($path in $FilePath) {
        $file = (Resolve-Path -LiteralPath $path).Path
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            & $Context.SignTool sign /fd SHA256 /f $Context.PfxPath /ac $Context.RootPath /d CardVault /tr $Context.TimestampUrl /td SHA256 $file
            if ($LASTEXITCODE -eq 0) { break }
            if ($attempt -eq 3) { throw "Signing or timestamping failed: $file" }
            Start-Sleep -Seconds 3
        }
        & $Context.SignTool verify /pa /all /tw $file
        if ($LASTEXITCODE -ne 0) { throw "Authenticode signature verification failed: $file" }
        $signature = Get-AuthenticodeSignature -LiteralPath $file
        if ($signature.Status -ne 'Valid' -or -not $signature.SignerCertificate -or
            $signature.SignerCertificate.Thumbprint -ne $Context.SignerThumbprint -or -not $signature.TimeStamperCertificate) {
            throw "Unexpected signing identity, invalid signature or missing timestamp: $file"
        }
    }
}

function Close-WindowsSigningContext {
    param([Parameter(Mandatory)]$Context)
    try {
        if ($Context.RootImported) {
            $store = [System.Security.Cryptography.X509Certificates.X509Store]::new('Root', 'CurrentUser')
            try {
                $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
                $store.Remove($Context.RootCertificate)
            } finally { $store.Dispose() }
        }
    } finally {
        if ($Context.RootCertificate) { $Context.RootCertificate.Dispose() }
        foreach ($path in @($Context.PfxPath, $Context.RootPath)) {
            if ($path -and [System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) }
        }
        if ([System.IO.Directory]::Exists($Context.Directory)) { [System.IO.Directory]::Delete($Context.Directory, $false) }
    }
}
