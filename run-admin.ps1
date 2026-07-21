Set-Location "$PSScriptRoot"

function Import-LocalEnvironment {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#') -or -not $text.Contains('=')) { continue }
        $pair = $text.Split('=', 2)
        $name = $pair[0].Trim()
        $value = $pair[1].Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { continue }
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

Import-LocalEnvironment -Path (Join-Path $PSScriptRoot '.env')
$env:SENSOR_ATTACHMENT_ROOT = Join-Path $PSScriptRoot '.local-data\attachments'
$env:RUOYI_PROFILE = Join-Path $PSScriptRoot '.local-data\uploadPath'
$env:INFERENCE_MODEL_ROOT = Join-Path $PSScriptRoot '.local-models'
if ([string]::IsNullOrWhiteSpace($env:INFERENCE_INTERNAL_TOKEN) -and
    -not [string]::IsNullOrWhiteSpace($env:SENSOR_INFERENCE_INTERNAL_TOKEN)) {
    $env:INFERENCE_INTERNAL_TOKEN = $env:SENSOR_INFERENCE_INTERNAL_TOKEN
}

Write-Host "[1/2] Cleaning and building all required modules..." -ForegroundColor Cyan
cmd /c "mvn clean install -DskipTests"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Module build failed, aborting startup." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/2] Starting packaged ruoyi-admin with active dev profile..." -ForegroundColor Cyan
$adminJar = Join-Path $PSScriptRoot "ruoyi-admin\target\ruoyi-admin.jar"
if (-not (Test-Path -LiteralPath $adminJar)) {
    Write-Host "Backend package not found: $adminJar" -ForegroundColor Red
    exit 1
}
& java -jar $adminJar --spring.profiles.active=dev
$backendExitCode = $LASTEXITCODE
if ($backendExitCode -in @(-1, 130, -1073741510)) {
    Write-Host "Backend process stopped." -ForegroundColor Yellow
    exit 0
}
if ($backendExitCode -ne 0) {
    Write-Host "Backend process exited abnormally. Review the first ERROR/Caused by message above." -ForegroundColor Red
    exit $backendExitCode
}
