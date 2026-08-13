param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$ModelSourceRoot,
    [Parameter(Mandatory = $true)][string]$JreRuntimeRoot,
    [Parameter(Mandatory = $true)][string]$PythonRuntimeRoot,
    [Parameter(Mandatory = $true)][string]$NginxRuntimeRoot,
    [Parameter(Mandatory = $true)][string]$WheelhouseRoot,
    [Parameter(Mandatory = $true)][string]$WinSWExecutable,
    [Parameter(Mandatory = $true)][string]$SigningCertificateThumbprint,
    [string]$OutputRoot = ".\offline-packages"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$modelSource = (Resolve-Path $ModelSourceRoot).Path
$jreSource = (Resolve-Path $JreRuntimeRoot).Path
$pythonSource = (Resolve-Path $PythonRuntimeRoot).Path
$nginxSource = (Resolve-Path $NginxRuntimeRoot).Path
$wheelhouseSource = (Resolve-Path $WheelhouseRoot).Path
$winswSource = (Resolve-Path $WinSWExecutable).Path
if (-not (Test-Path -LiteralPath $winswSource -PathType Leaf)) { throw "WinSWExecutable 必须是文件" }
$certificate = Get-Item -LiteralPath "Cert:\CurrentUser\My\$SigningCertificateThumbprint" -ErrorAction Stop
if (-not $certificate.HasPrivateKey) { throw "签名证书不含私钥: $SigningCertificateThumbprint" }
$modelManifestPath = Join-Path $repo "ruoyi-sensor\inference\models-manifest.json"
$modelManifest = Get-Content -LiteralPath $modelManifestPath -Raw | ConvertFrom-Json
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

    Push-Location "ruoyi-sensor\mock"
    try { mvn clean test package } finally { Pop-Location }

    New-Item -ItemType Directory -Path "$stage\backend","$stage\frontend","$stage\inference","$stage\models","$stage\deployment","$stage\runtime","$stage\wheelhouse","$stage\gateway" | Out-Null
    Copy-Item "ruoyi-admin\target\ruoyi-admin.jar" "$stage\backend\ruoyi-admin.jar"
    Copy-Item "target\backend-sbom.json" "$stage\backend\backend-sbom.json"
    Copy-Item "ruoyi-ui\dist\*" "$stage\frontend" -Recurse
    Copy-Item "ruoyi-ui\frontend-sbom.json" "$stage\frontend\frontend-sbom.json"
    Copy-Item "ruoyi-sensor\inference\*.py" "$stage\inference"
    Copy-Item "ruoyi-sensor\inference\requirements.txt" "$stage\inference"
    Copy-Item $modelManifestPath "$stage\inference\models-manifest.json"
    Copy-Item "ruoyi-sensor\inference\models" "$stage\inference\models" -Recurse
    Copy-Item "ruoyi-sensor\inference\utils" "$stage\inference\utils" -Recurse
    Copy-Item "ruoyi-sensor\mock\target\vibration-simulator-1.0.0.jar" "$stage\gateway\phm-edge-gateway.jar"
    foreach ($model in $modelManifest.models) {
        $source = Join-Path $modelSource $model.artifact
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "缺少模型制品: $source"
        }
        $actualHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $model.sha256) {
            throw "模型制品哈希不匹配: $($model.artifact)"
        }
        Copy-Item -LiteralPath $source -Destination (Join-Path "$stage\models" $model.artifact)
    }
    Copy-Item "deployment\*" "$stage\deployment" -Recurse
    Copy-Item -LiteralPath $jreSource -Destination "$stage\runtime\jre17" -Recurse
    Copy-Item -LiteralPath $pythonSource -Destination "$stage\runtime\python" -Recurse
    Copy-Item -LiteralPath $nginxSource -Destination "$stage\runtime\nginx" -Recurse
    Copy-Item -Path (Join-Path $wheelhouseSource "*") -Destination "$stage\wheelhouse" -Recurse
    foreach ($service in @("phm-platform","phm-inference","phm-nginx")) {
        Copy-Item -LiteralPath $winswSource -Destination "$stage\deployment\winsw\$service.exe"
    }
    Copy-Item "ruoyi-admin\target\classes\db\migration" "$stage\deployment\flyway-migrations" -Recurse

    $manifest = Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        [pscustomobject]@{
            path = $_.FullName.Substring($stage.Length + 1).Replace('\','/')
            sha256 = $hash.Hash.ToLowerInvariant()
            size = $_.Length
        }
    }
    $manifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath "$stage\SHA256SUMS.json" -Encoding UTF8
    $zipPath = Join-Path $output "phm-$Version.zip"
    Compress-Archive -Path "$stage\*" -DestinationPath $zipPath -Force

    $content = [System.Security.Cryptography.Pkcs.ContentInfo]::new(
        [System.IO.File]::ReadAllBytes($zipPath))
    $signedCms = [System.Security.Cryptography.Pkcs.SignedCms]::new($content, $true)
    $cmsSigner = [System.Security.Cryptography.Pkcs.CmsSigner]::new($certificate)
    $signedCms.ComputeSignature($cmsSigner)
    [System.IO.File]::WriteAllBytes("$zipPath.p7s", $signedCms.Encode())
    [System.IO.File]::WriteAllBytes(
        (Join-Path $output "phm-$Version-signing-cert.cer"),
        $certificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
}
finally { Pop-Location }
