# 启动统一的齿轮/轴承内部推理服务
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Starting Diagnosis Services" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

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

Import-LocalEnvironment -Path (Join-Path $scriptDir '.env')
$env:SENSOR_ATTACHMENT_ROOT = Join-Path $scriptDir '.local-data\attachments'
$env:RUOYI_PROFILE = Join-Path $scriptDir '.local-data\uploadPath'
$env:INFERENCE_MODEL_ROOT = Join-Path $scriptDir '.local-models'
if ([string]::IsNullOrWhiteSpace($env:INFERENCE_INTERNAL_TOKEN) -and
    -not [string]::IsNullOrWhiteSpace($env:SENSOR_INFERENCE_INTERNAL_TOKEN)) {
    $env:INFERENCE_INTERNAL_TOKEN = $env:SENSOR_INFERENCE_INTERNAL_TOKEN
}
if ([string]::IsNullOrWhiteSpace($env:SENSOR_INFERENCE_INTERNAL_TOKEN) -and
    -not [string]::IsNullOrWhiteSpace($env:INFERENCE_INTERNAL_TOKEN)) {
    $env:SENSOR_INFERENCE_INTERNAL_TOKEN = $env:INFERENCE_INTERNAL_TOKEN
}

$pythonExe = Join-Path $scriptDir ".venv\Scripts\python.exe"
$servicePath = Join-Path $scriptDir "ruoyi-sensor\inference\inference_service.py"
$workDir = Join-Path $scriptDir "ruoyi-sensor\inference"

if (-not (Test-Path $pythonExe)) {
    Write-Host "项目虚拟环境不存在: $pythonExe" -ForegroundColor Red
    Write-Host "请先创建并安装依赖:" -ForegroundColor Yellow
    Write-Host "  py -3.11 -m venv .venv"
    Write-Host "  .\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt"
    exit 1
}

& $pythonExe -c "import torch" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "当前 .venv 缺少 torch，不能启动推理服务。" -ForegroundColor Red
    Write-Host "请执行:" -ForegroundColor Yellow
    Write-Host "  .\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt"
    exit 1
}

Write-Host "Using Python: $pythonExe" -ForegroundColor DarkCyan

if ([string]::IsNullOrWhiteSpace($env:INFERENCE_INTERNAL_TOKEN)) {
    Write-Host "INFERENCE_INTERNAL_TOKEN 未设置，拒绝启动内部推理服务。" -ForegroundColor Red
    exit 1
}
if ([string]::IsNullOrWhiteSpace($env:GEAR_MODEL_PATH)) {
    $env:GEAR_MODEL_PATH = Join-Path $scriptDir ".local-models\best_model_classwise_maha.pth"
}
if ([string]::IsNullOrWhiteSpace($env:BEARING_MODEL_PATH)) {
    $env:BEARING_MODEL_PATH = Join-Path $scriptDir ".local-models\best_model.pth"
}
if ([string]::IsNullOrWhiteSpace($env:GEAR_MODEL_SHA256)) {
    $env:GEAR_MODEL_SHA256 = "b315315a5af91421813c5f452cff1f0315b0029a22158d5f2fa44d59a4d870aa"
}
if ([string]::IsNullOrWhiteSpace($env:BEARING_MODEL_SHA256)) {
    $env:BEARING_MODEL_SHA256 = "5773424dba27357bbcf756172b3cb8e19c6eebc1510c29169b7e638b3a6fdc30"
}
if ([string]::IsNullOrWhiteSpace($env:INFERENCE_ALLOWED_INPUT_ROOTS)) {
    $env:INFERENCE_ALLOWED_INPUT_ROOTS = Join-Path $env:SENSOR_ATTACHMENT_ROOT "objects"
}
Write-Host "Starting unified inference service (127.0.0.1:5000)..." -ForegroundColor Yellow
Start-Process -FilePath $pythonExe -ArgumentList $servicePath -WorkingDirectory $workDir -WindowStyle Hidden

Write-Host ""
Write-Host "Unified inference service started." -ForegroundColor Green
Write-Host "  Ready: http://127.0.0.1:5000/internal/health/ready" -ForegroundColor Cyan
