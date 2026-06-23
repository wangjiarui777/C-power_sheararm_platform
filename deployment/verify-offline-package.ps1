param(
    [Parameter(Mandatory = $true)][string]$PackagePath,
    [Parameter(Mandatory = $true)][string]$SignaturePath,
    [Parameter(Mandatory = $true)][string]$ExpectedCertificateThumbprint
)
$ErrorActionPreference = "Stop"
$package = (Resolve-Path $PackagePath).Path
$signature = (Resolve-Path $SignaturePath).Path
$content = [System.Security.Cryptography.Pkcs.ContentInfo]::new(
    [System.IO.File]::ReadAllBytes($package))
$signedCms = [System.Security.Cryptography.Pkcs.SignedCms]::new($content, $true)
$signedCms.Decode([System.IO.File]::ReadAllBytes($signature))
$signedCms.CheckSignature($true)
$actual = $signedCms.SignerInfos[0].Certificate.Thumbprint.Replace(" ", "").ToUpperInvariant()
$expected = $ExpectedCertificateThumbprint.Replace(" ", "").ToUpperInvariant()
if ($actual -ne $expected) {
    throw "签名证书指纹不匹配，期望 $expected，实际 $actual"
}
Write-Output "Offline package signature verified: $package"
