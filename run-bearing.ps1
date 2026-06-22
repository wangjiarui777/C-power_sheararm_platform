# 启动轴承诊断推理服务 (端口 5001)
$env:BEARING_PORT = "5001"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonExe = Join-Path $scriptDir ".venv\Scripts\python.exe"
$servicePath = Join-Path $scriptDir "ruoyi-sensor\inference\bearing_service.py"

if (-not (Test-Path $pythonExe)) {
    Write-Host "项目虚拟环境不存在: $pythonExe" -ForegroundColor Red
    Write-Host "请先创建并安装依赖:" -ForegroundColor Yellow
    Write-Host "  py -3.11 -m venv .venv"
    Write-Host "  .\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt"
    exit 1
}

& $pythonExe -c "import torch" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "当前 .venv 缺少 torch，不能启动轴承推理服务。" -ForegroundColor Red
    Write-Host "请执行:" -ForegroundColor Yellow
    Write-Host "  .\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt"
    exit 1
}

Write-Host "Starting Bearing Diagnosis Service on port 5001..." -ForegroundColor Cyan
Write-Host "Using Python: $pythonExe" -ForegroundColor DarkCyan
& $pythonExe $servicePath
