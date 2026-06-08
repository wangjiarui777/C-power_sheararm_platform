# =============================================================================
# create-shortcut.ps1 — 在桌面创建 start-all.ps1 的快捷方式
# =============================================================================

$projectRoot = $PSScriptRoot
$startScript = "$projectRoot\start-all.ps1"

# 桌面路径
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = "$desktop\启动毕设服务.lnk"

# 创建 WScript.Shell 对象
$ws = New-Object -ComObject WScript.Shell
$sc = $ws.CreateShortcut($shortcutPath)

$sc.TargetPath       = "powershell.exe"
$sc.Arguments        = "-ExecutionPolicy Bypass -NoExit -File `"$startScript`""
$sc.WorkingDirectory = $projectRoot
$sc.WindowStyle      = 1   # 1 = 正常窗口，3 = 最大化，7 = 最小化
$sc.Description      = "一键启动：后端 + 前端 + 数据接收 + 推理服务"
$sc.IconLocation     = "powershell.exe,0"

$sc.Save()

Write-Host "快捷方式已创建：" -ForegroundColor Green
Write-Host "  $shortcutPath" -ForegroundColor White
Write-Host ""
Write-Host "双击桌面上的「启动毕设服务」即可一键启动全部服务。" -ForegroundColor Cyan
