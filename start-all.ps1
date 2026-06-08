# =============================================================================
# start-all.ps1 — 一键启动所有开发服务
# 每个服务在独立的 PowerShell 窗口中运行，互不阻塞。
# =============================================================================

$projectRoot = $PSScriptRoot
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 辅助函数：将命令编码为 Base64 后在新的 PowerShell 窗口中执行
# 避免多层嵌套引号带来的转义问题
# ---------------------------------------------------------------------------
function Start-InNewWindow {
    param(
        [string]$Title,
        [string]$WorkingDir,
        [string]$Command
    )

    # 构造在新窗口中要执行的脚本（先切目录，再执行命令）
    $script = @"
Set-Location '$WorkingDir'
Write-Host '========================================' -ForegroundColor DarkGray
Write-Host "  $Title" -ForegroundColor Yellow
Write-Host '========================================' -ForegroundColor DarkGray
$Command
Write-Host ''
Write-Host '>>> $Title 已退出。可关闭此窗口。' -ForegroundColor DarkYellow
Read-Host
"@

    $bytes   = [System.Text.Encoding]::Unicode.GetBytes($script)
    $encoded = [Convert]::ToBase64String($bytes)

    Start-Process powershell `
        -ArgumentList "-NoExit", "-Command", "`$Host.UI.RawUI.WindowTitle = '$Title'; powershell -EncodedCommand $encoded"
}

# =============================================================================
# 1. Spring Boot 后端 (ruoyi-admin)
# =============================================================================
Start-InNewWindow `
    -Title 'Spring Boot Admin (后端)' `
    -WorkingDir "$projectRoot" `
    -Command @'
Write-Host '[1/2] mvn clean install -DskipTests ...' -ForegroundColor Cyan
cmd /c "mvn clean install -DskipTests"
if ($LASTEXITCODE -ne 0) {
    Write-Host '模块构建失败！请检查错误信息。' -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host '[2/2] 启动 ruoyi-admin (dev profile)...' -ForegroundColor Cyan
Set-Location ruoyi-admin
cmd /c "mvn -U spring-boot:run -Dspring-boot.run.profiles=dev"
'@

# 给 Maven 一点时间开始构建
Start-Sleep -Seconds 3

# =============================================================================
# 2. Vue 前端 (ruoyi-ui)
# =============================================================================
Start-InNewWindow `
    -Title 'Vue Frontend (前端)' `
    -WorkingDir "$projectRoot\ruoyi-ui" `
    -Command @'
Write-Host 'npm run dev ...' -ForegroundColor Cyan
npm run dev
'@

Start-Sleep -Seconds 1

# =============================================================================
# 3. CwruMatReceiver (Java 数据接收)
# =============================================================================
Start-InNewWindow `
    -Title 'CwruMatReceiver (数据接收 :8889)' `
    -WorkingDir "$projectRoot\get" `
    -Command @'
Write-Host '[1/2] javac CwruMatReceiver.java ...' -ForegroundColor Cyan
cmd /c "javac CwruMatReceiver.java"
if ($LASTEXITCODE -ne 0) {
    Write-Host '编译失败！请检查 Java 环境。' -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host '[2/2] java CwruMatReceiver 8889 got ...' -ForegroundColor Cyan
cmd /c "java CwruMatReceiver 8889 got"
'@

Start-Sleep -Seconds 1

# =============================================================================
# 4. Python 推理服务 (inference_service.py  :5001)
# =============================================================================
Start-InNewWindow `
    -Title 'Inference Service (Python :5001)' `
    -WorkingDir "$projectRoot" `
    -Command @'
Write-Host 'python inference_service.py ...' -ForegroundColor Cyan
python inference_service.py
'@

# =============================================================================
Write-Host ''
Write-Host '========================================' -ForegroundColor Green
Write-Host '  全部启动完成！请检查各服务窗口。' -ForegroundColor Green
Write-Host '========================================' -ForegroundColor Green
Write-Host ''
Write-Host '  后端:        http://localhost:8080' -ForegroundColor White
Write-Host '  前端:        http://localhost:80'   -ForegroundColor White
Write-Host '  数据接收:    :8889'                  -ForegroundColor White
Write-Host '  推理服务:    http://localhost:5001'  -ForegroundColor White
Write-Host ''

Read-Host '按 Enter 键退出此启动器窗口（不影响其他服务）'
