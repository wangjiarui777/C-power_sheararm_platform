# 同时启动齿轮和轴承诊断推理服务
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Starting Diagnosis Services" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonExe = Join-Path $scriptDir ".venv\Scripts\python.exe"
$gearPath = Join-Path $scriptDir "ruoyi-sensor\inference\gear_service.py"
$bearingPath = Join-Path $scriptDir "ruoyi-sensor\inference\bearing_service.py"
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

Write-Host "[1/2] Starting Gear Service (port 5000)..." -ForegroundColor Yellow
Start-Process -FilePath $pythonExe -ArgumentList $gearPath -WorkingDirectory $workDir

Write-Host "[2/2] Starting Bearing Service (port 5001)..." -ForegroundColor Yellow
Start-Process -FilePath $pythonExe -ArgumentList $bearingPath -WorkingDirectory $workDir

Write-Host ""
Write-Host "Both services started in separate windows." -ForegroundColor Green
Write-Host "  Gear:    http://127.0.0.1:5000/health" -ForegroundColor Cyan
Write-Host "  Bearing: http://127.0.0.1:5001/health" -ForegroundColor Cyan
