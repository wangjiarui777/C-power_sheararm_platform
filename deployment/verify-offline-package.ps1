param([Parameter(Mandatory = $true)][string]$PackageDirectory)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path $PackageDirectory).Path
$manifest = Get-Content -LiteralPath (Join-Path $root "SHA256SUMS.json") -Raw | ConvertFrom-Json
foreach ($entry in $manifest) {
    $path = Join-Path $root $entry.path
    if (-not (Test-Path $path)) { throw "Missing package file: $($entry.path)" }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $entry.sha256) { throw "Hash mismatch: $($entry.path)" }
}
Write-Host "Offline package hash verification passed."
