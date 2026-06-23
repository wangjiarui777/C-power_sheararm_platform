param([Parameter(Mandatory = $true)][string]$Version)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$release = Join-Path $root "releases\$Version"
if (-not (Test-Path $release)) { throw "Release does not exist: $release" }

$current = Join-Path $root "current"
$previous = Join-Path $root "previous"
Stop-Service phm-nginx, phm-platform, phm-inference
if (Test-Path $previous) { Remove-Item -LiteralPath $previous -Force }
if (Test-Path $current) {
    $oldTarget = (Get-Item -LiteralPath $current).Target
    New-Item -ItemType Junction -Path $previous -Target $oldTarget | Out-Null
    Remove-Item -LiteralPath $current -Force
}
New-Item -ItemType Junction -Path $current -Target $release | Out-Null
Start-Service phm-inference, phm-platform, phm-nginx
