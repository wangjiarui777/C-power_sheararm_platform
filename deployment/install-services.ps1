$ErrorActionPreference = "Stop"
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
$winsw = Join-Path $base "winsw\winsw.exe"
if (-not (Test-Path $winsw)) { throw "winsw.exe is missing: $winsw" }

$services = @("phm-inference", "phm-platform", "phm-nginx")
foreach ($service in $services) {
    $xml = Join-Path $base "winsw\$service.xml"
    & $winsw install $xml
}
foreach ($service in $services) { Start-Service -Name $service }
