param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$OutputRoot = ".\offline-packages"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path $OutputRoot)) { New-Item -ItemType Directory -Path $OutputRoot | Out-Null }
$output = (Resolve-Path $OutputRoot).Path
$stage = Join-Path $output $Version
if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null

Push-Location $repo
try {
    mvn clean test package
    mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom -DoutputName=backend-sbom
    Push-Location "ruoyi-ui"
    try {
        npm ci
        npm audit --omit=dev --audit-level=high
        npm run build:prod
        npm sbom --sbom-format cyclonedx | Set-Content -LiteralPath "frontend-sbom.json" -Encoding UTF8
    } finally { Pop-Location }

    New-Item -ItemType Directory -Path "$stage\backend","$stage\frontend","$stage\inference","$stage\deployment" | Out-Null
    Copy-Item "ruoyi-admin\target\ruoyi-admin.jar" "$stage\backend\ruoyi-admin.jar"
    Copy-Item "target\backend-sbom.json" "$stage\backend\backend-sbom.json"
    Copy-Item "ruoyi-ui\dist\*" "$stage\frontend" -Recurse
    Copy-Item "ruoyi-ui\frontend-sbom.json" "$stage\frontend\frontend-sbom.json"
    Copy-Item "ruoyi-sensor\inference\*" "$stage\inference" -Recurse
    Copy-Item "deployment\*" "$stage\deployment" -Recurse

    $manifest = Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        [pscustomobject]@{
            path = $_.FullName.Substring($stage.Length + 1).Replace('\','/')
            sha256 = $hash.Hash.ToLowerInvariant()
            size = $_.Length
        }
    }
    $manifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath "$stage\SHA256SUMS.json" -Encoding UTF8
    Compress-Archive -Path "$stage\*" -DestinationPath (Join-Path $output "phm-$Version.zip") -Force
}
finally { Pop-Location }
