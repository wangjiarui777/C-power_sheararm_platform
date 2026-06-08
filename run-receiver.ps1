Set-Location "$PSScriptRoot\get"

$ErrorActionPreference = 'Stop'

Write-Host '[1/2] Compiling CwruMatReceiver.java...' -ForegroundColor Cyan
cmd /c "javac CwruMatReceiver.java"
if ($LASTEXITCODE -ne 0) {
    Write-Host '编译失败，已停止。' -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host '[2/2] Starting CwruMatReceiver...' -ForegroundColor Cyan
cmd /c "java CwruMatReceiver 8889 got"
