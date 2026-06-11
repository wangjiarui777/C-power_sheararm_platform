# 启动轴承诊断推理服务 (端口 5001)
$env:BEARING_PORT = "5001"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$servicePath = Join-Path $scriptDir "ruoyi-sensor\inference\bearing_service.py"
Write-Host "Starting Bearing Diagnosis Service on port 5001..." -ForegroundColor Cyan
python $servicePath
