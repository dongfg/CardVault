# Shared by Windows packaging. Dot-sourcing this file does not download or install anything.
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'build-logging.ps1')

function New-WindowsSigningContext {
    param(
        [uri]$CertificateUrl = 'https://cdn.dongfg.com/certs/codesign.pfx',
        [uri]$RootCertificateUrl = 'https://cdn.dongfg.com/certs/root-ca.cer',
        [string]$RootSha256 = '5E196B56432C816D7D53638B0BF5EF87E50B3211C5CD325DF4E266439D9AFB96',
        [uri]$TimestampUrl = 'http://timestamp.digicert.com',
        [switch]$TrustRoot
    )
    $ErrorActionPreference = 'Stop'
    Write-BuildLog 'Locate Windows SDK SignTool'
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
    Write-BuildLog "SignTool: $signTool"
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
        Get-BuildDownload -Uri $RootCertificateUrl -Destination $context.RootPath -Label 'root certificate'
        Get-BuildDownload -Uri $CertificateUrl -Destination $context.PfxPath -Label 'signing PFX'
        Write-BuildLog 'Validate certificate fingerprint, empty-password PFX and code-signing chain'
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
        $chain.ChainPolicy.DisableCertificateDownloads = $true
        $chain.ChainPolicy.UrlRetrievalTimeout = [TimeSpan]::FromSeconds(10)
        $chain.ChainPolicy.ApplicationPolicy.Add([System.Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.3')) | Out-Null
        if (-not $chain.Build($signer)) { throw 'Signing certificate chain, validity or code-signing purpose is invalid' }
        $context.SignerThumbprint = $signer.Thumbprint
        if ($TrustRoot) {
            Write-BuildLog 'Add temporary root trust on CI runner'
            # Trust is added only on the disposable CI runner and removed on completion.
            $store = [System.Security.Cryptography.X509Certificates.X509Store]::new('Root', 'CurrentUser')
            try {
                $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
                $existing = $store.Certificates.Find([System.Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,
                    $context.RootCertificate.Thumbprint, $false)
                if ($existing.Count -eq 0) { $store.Add($context.RootCertificate); $context.RootImported = $true }
            } finally { $store.Dispose() }
        }
        Write-BuildLog "Certificate validation passed; signing identity: $($signer.Subject)"
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
            try {
                Write-BuildLog "Timestamp server: $($Context.TimestampUrl)"
                Invoke-BuildProcess -FilePath $Context.SignTool -Label "Sign + timestamp $([IO.Path]::GetFileName($file)) attempt $attempt" -TimeoutSeconds 90 -ArgumentList @(
                    'sign','/v','/fd','SHA256','/f',$Context.PfxPath,'/ac',$Context.RootPath,'/d','CardVault','/tr',$Context.TimestampUrl,'/td','SHA256',$file)
                break
            } catch {
                Write-BuildLog "SIGN FAILED: $($_.Exception.Message)"
                if ($attempt -eq 3) { throw }
                Start-Sleep -Seconds 3
            }
        }
        Invoke-BuildProcess -FilePath $Context.SignTool -Label "Verify $([IO.Path]::GetFileName($file))" -TimeoutSeconds 60 -ArgumentList @('verify','/v','/pa','/all','/tw',$file)
        # Isolate PowerShell's chain/trust evaluation too; it can perform network retrieval.
        $probe = @'
param($Path, $ExpectedSigner)
$ErrorActionPreference = 'Stop'
$signature = Get-AuthenticodeSignature -LiteralPath $Path
if ($signature.Status -ne 'Valid' -or -not $signature.SignerCertificate -or
    $signature.SignerCertificate.Thumbprint -ne $ExpectedSigner -or -not $signature.TimeStamperCertificate) {
    throw 'Unexpected signing identity, invalid signature or missing timestamp'
}
Write-Host "Authenticode status: $($signature.Status); signer: $($signature.SignerCertificate.Subject)"
'@
        $probePath = Join-Path $Context.Directory 'verify-authenticode.ps1'
        [IO.File]::WriteAllText($probePath, $probe)
        Invoke-BuildProcess -FilePath (Join-Path $PSHOME 'pwsh.exe') -Label "Authenticode policy $([IO.Path]::GetFileName($file))" -TimeoutSeconds 60 -ArgumentList @(
            '-NoProfile','-NonInteractive','-File',$probePath,$file,$Context.SignerThumbprint)
    }
}

function Close-WindowsSigningContext {
    param([Parameter(Mandatory)]$Context)
    Write-BuildLog 'Clean up signing certificate files and temporary CI root trust'
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
        foreach ($path in @($Context.PfxPath, $Context.RootPath, (Join-Path $Context.Directory 'verify-authenticode.ps1'))) {
            if ($path -and [System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) }
        }
        if ([System.IO.Directory]::Exists($Context.Directory)) { [System.IO.Directory]::Delete($Context.Directory, $false) }
    }
}
