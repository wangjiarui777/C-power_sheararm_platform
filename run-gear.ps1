# 启动齿轮诊断推理服务 (端口 5000)
$env:PORT = "5000"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$servicePath = Join-Path $scriptDir "ruoyi-sensor\inference\gear_service.py"
Write-Host "Starting Gear Diagnosis Service on port 5000..." -ForegroundColor Cyan
python $servicePath
