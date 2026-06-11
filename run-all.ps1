# 同时启动齿轮和轴承诊断推理服务
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Starting Diagnosis Services" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gearPath = Join-Path $scriptDir "ruoyi-sensor\inference\gear_service.py"
$bearingPath = Join-Path $scriptDir "ruoyi-sensor\inference\bearing_service.py"

Write-Host "[1/2] Starting Gear Service (port 5000)..." -ForegroundColor Yellow
Start-Process python -ArgumentList $gearPath -WorkingDirectory (Join-Path $scriptDir "ruoyi-sensor\inference")

Write-Host "[2/2] Starting Bearing Service (port 5001)..." -ForegroundColor Yellow
Start-Process python -ArgumentList $bearingPath -WorkingDirectory (Join-Path $scriptDir "ruoyi-sensor\inference")

Write-Host ""
Write-Host "Both services started in separate windows." -ForegroundColor Green
Write-Host "  Gear:    http://127.0.0.1:5000/health" -ForegroundColor Cyan
Write-Host "  Bearing: http://127.0.0.1:5001/health" -ForegroundColor Cyan
