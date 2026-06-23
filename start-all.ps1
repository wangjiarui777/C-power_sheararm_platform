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

function Get-PortProcessId {
    param([Parameter(Mandatory = $true)][int]$Port)
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $conn) {
        return $conn.OwningProcess
    }
    return $null
}

function Stop-PortProcess {
    param([Parameter(Mandatory = $true)][int]$Port)
    $processId = Get-PortProcessId -Port $Port
    if ($null -eq $processId) {
        return $false
    }
    try {
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Write-Host "[OK] Stopped process $processId on port $Port" -ForegroundColor Yellow
        Start-Sleep -Seconds 2
        return $true
    } catch {
        Write-Host "[WARN] Failed to stop process $processId on port $Port : $($_.Exception.Message)" -ForegroundColor Yellow
        return $false
    }
}

function Ensure-PortFree {
    param([Parameter(Mandatory = $true)][int]$Port)
    while (Get-PortProcessId -Port $Port) {
        if (-not (Stop-PortProcess -Port $Port)) {
            break
        }
    }
}

function Resolve-Python {
    $pythonExe = Join-Path $projectRoot ".venv\Scripts\python.exe"
    if (-not (Test-Path $pythonExe)) {
        throw @"
项目虚拟环境不存在: $pythonExe
请先创建并安装依赖:
  py -3.11 -m venv .venv
  .\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt
"@
    }

    return (Resolve-Path $pythonExe).Path
}

function Test-PythonDeps {
    param([Parameter(Mandatory = $true)][string]$PythonExe)

    & $PythonExe -c "import torch" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw @"
当前 .venv 缺少 torch，推理服务无法启动。
请执行:
  .\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt
"@
    }
}

# =============================================================================
# 1. Spring Boot 后端 (ruoyi-admin)
# =============================================================================
Ensure-PortFree -Port 8080
Ensure-PortFree -Port 9528
Ensure-PortFree -Port 5000
Ensure-PortFree -Port 5001
Ensure-PortFree -Port 8888
Ensure-PortFree -Port 8890
Ensure-PortFree -Port 8891

$pythonExe = Resolve-Python
Test-PythonDeps -PythonExe $pythonExe
Write-Host "[OK] Using Python: $pythonExe" -ForegroundColor DarkCyan

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
# 3. Python 统一内部推理服务（齿轮 + 轴承）
# =============================================================================
Start-InNewWindow `
    -Title 'PHM Internal Inference (:5000)' `
    -WorkingDir "$projectRoot\ruoyi-sensor\inference" `
    -Command @"
`$pythonExe = '$pythonExe'
if ([string]::IsNullOrWhiteSpace(`$env:INFERENCE_INTERNAL_TOKEN)) {
    throw 'INFERENCE_INTERNAL_TOKEN 未设置，拒绝启动内部推理服务。'
}
`$env:GEAR_MODEL_PATH = '$projectRoot\.local-models\best_model_classwise_maha.pth'
`$env:BEARING_MODEL_PATH = '$projectRoot\.local-models\best_model.pth'
`$env:GEAR_MODEL_SHA256 = 'b315315a5af91421813c5f452cff1f0315b0029a22158d5f2fa44d59a4d870aa'
`$env:BEARING_MODEL_SHA256 = '5773424dba27357bbcf756172b3cb8e19c6eebc1510c29169b7e638b3a6fdc30'
`$attachmentRoot = if (`$env:SENSOR_ATTACHMENT_ROOT) { `$env:SENSOR_ATTACHMENT_ROOT } else { 'D:\ruoyi-secure\attachments' }
`$env:INFERENCE_ALLOWED_INPUT_ROOTS = Join-Path `$attachmentRoot 'objects'
Write-Host 'Starting unified internal inference service on 127.0.0.1:5000...' -ForegroundColor Cyan
Write-Host "Using Python: `$pythonExe" -ForegroundColor DarkCyan
& `$pythonExe inference_service.py
"@

Write-Host ''
Write-Host '========================================' -ForegroundColor Green
Write-Host '  全部启动完成！请检查各服务窗口。' -ForegroundColor Green
Write-Host '========================================' -ForegroundColor Green
Write-Host ''
Write-Host '  后端:        http://localhost:8080' -ForegroundColor White
Write-Host '  前端:        http://localhost:80'   -ForegroundColor White
Write-Host '  内部推理:    http://127.0.0.1:5000/internal/*' -ForegroundColor Cyan
Write-Host ''

Read-Host '按 Enter 键退出此启动器窗口（不影响其他服务）'
