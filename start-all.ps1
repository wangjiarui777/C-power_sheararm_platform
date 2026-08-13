# =============================================================================
# start-all.ps1 - 当前开发架构的一键启动器
#
# 架构：Vue(80) -> Spring Boot(8080) -> FastAPI 统一推理(5000)
#                         -> Apache IoTDB ConfigNode/DataNode(10710/6667)
#       采集接收端口 8888/8890/8891/9000 由 Spring Boot 开发配置启动。
#       诊断测点总览由 Vue 路由承载，聚合接口随 Spring Boot 一并启动。
# 所有模型、附件、上传文件、日志和运行状态都保存在本项目目录内。
# =============================================================================

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$ForcePortCleanup,
    [ValidateRange(1, 65535)]
    [int]$FrontendPort = 80
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$inferenceDir = Join-Path $projectRoot 'ruoyi-sensor\inference'
$frontendDir = Join-Path $projectRoot 'ruoyi-ui'
$adminJar = Join-Path $projectRoot 'ruoyi-admin\target\ruoyi-admin.jar'
$pythonExe = Join-Path $projectRoot '.venv\Scripts\python.exe'
$defaultIotdbHome = 'C:\iotdb\apache-iotdb-2.0.8-all-bin'
$modelRoot = Join-Path $projectRoot '.local-models'
$dataRoot = Join-Path $projectRoot '.local-data'
$attachmentRoot = Join-Path $dataRoot 'attachments'
$uploadRoot = Join-Path $dataRoot 'uploadPath'
$logRoot = Join-Path $dataRoot 'logs'
$runRoot = Join-Path $dataRoot 'run'
$pidFile = Join-Path $runRoot 'service-pids.json'
$diagnosisOverviewPath = '/analysis-toolkit/bearing-diagnosis'

if ($projectRoot -match '(?i)\\OneDrive\\') {
    throw "拒绝从 OneDrive 路径启动。请从本地项目目录运行：C:\Users\123\Desktop\BiShe\RuoYi-Vue-master"
}

foreach ($path in @($dataRoot, $attachmentRoot, (Join-Path $attachmentRoot 'objects'),
        $uploadRoot, $logRoot, $runRoot)) {
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}

function Write-Step {
    param([string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

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

function Assert-Command {
    param([Parameter(Mandatory = $true)][string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "缺少命令：$Name"
    }
    return $command.Source
}

function Test-TcpEndpoint {
    param(
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMs = 1200
    )
    $client = New-Object Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-PortProcessId {
    param([Parameter(Mandatory = $true)][int]$Port)
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $connection) { return $null }
    return [int]$connection.OwningProcess
}

function Test-IsProjectProcess {
    param([Parameter(Mandatory = $true)][int]$Id)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$Id" -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $false }
    $commandLine = [string]$process.CommandLine
    if ($commandLine.IndexOf($projectRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    if (-not [string]::IsNullOrWhiteSpace($script:iotdbHome) -and
        $commandLine.IndexOf($script:iotdbHome, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    # 开发环境的 MAT 接收器以类名启动，命令行不包含工作目录。
    return $commandLine -match '(^|\s)CwruMatReceiver(\s|$)'
}

function Stop-Listener {
    param([Parameter(Mandatory = $true)][int]$Port)
    $ownerId = Get-PortProcessId -Port $Port
    if ($null -eq $ownerId) { return }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerId" -ErrorAction SilentlyContinue
    $isProjectProcess = Test-IsProjectProcess -Id $ownerId
    if (-not $isProjectProcess -and -not $ForcePortCleanup) {
        $description = if ($process) { "$($process.Name) / $($process.CommandLine)" } else { "PID $ownerId" }
        throw "端口 $Port 被非本项目进程占用：$description。确认可终止后使用 -ForcePortCleanup。"
    }
    Stop-Process -Id $ownerId -Force -ErrorAction Stop
    Write-Host "[STOP] port=$Port pid=$ownerId" -ForegroundColor Yellow
    $deadline = (Get-Date).AddSeconds(8)
    while ((Get-PortProcessId -Port $Port) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 250
    }
    if (Get-PortProcessId -Port $Port) {
        throw "端口 $Port 未能释放"
    }
}

function Wait-HttpReady {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Url,
        [hashtable]$Headers = @{},
        [int]$TimeoutSeconds = 90
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = ''
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -Headers $Headers -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Host "[READY] $Name -> $Url" -ForegroundColor Green
                return
            }
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name 在 ${TimeoutSeconds}s 内未就绪：$lastError"
}

function Show-LogTail {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        Write-Host "---- $Path ----" -ForegroundColor DarkYellow
        Get-Content -LiteralPath $Path -Tail 60
    }
}

function Start-LoggedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )
    $stdout = Join-Path $logRoot ($Name + '.out.log')
    $stderr = Join-Path $logRoot ($Name + '.err.log')
    $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Write-Host "[START] $Name pid=$($process.Id)" -ForegroundColor DarkCyan
    return @{
        name = $Name
        pid = $process.Id
        process = $process
        stdout = $stdout
        stderr = $stderr
    }
}

Import-LocalEnvironment -Path (Join-Path $projectRoot '.env')

$iotdbHomeCandidate = if ([string]::IsNullOrWhiteSpace($env:IOTDB_HOME)) {
    $defaultIotdbHome
} else {
    $env:IOTDB_HOME
}
$script:iotdbHome = [IO.Path]::GetFullPath($iotdbHomeCandidate)
$iotdbWindowsSbin = Join-Path $script:iotdbHome 'sbin\windows'
$iotdbConfigNodeScript = Join-Path $iotdbWindowsSbin 'start-confignode.bat'
$iotdbDataNodeScript = Join-Path $iotdbWindowsSbin 'start-datanode.bat'

# 强制使用项目内路径和当前三进程架构，避免继承到 OneDrive 或历史服务配置。
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:SENSOR_ATTACHMENT_ROOT = $attachmentRoot
$env:RUOYI_PROFILE = $uploadRoot
$env:INFERENCE_MODEL_ROOT = $modelRoot
$env:INFERENCE_ALLOWED_INPUT_ROOTS = Join-Path $attachmentRoot 'objects'
$env:SENSOR_GEAR_INFER_URL = 'http://127.0.0.1:5000/internal/infer'
$env:SENSOR_BEARING_INFER_URL = 'http://127.0.0.1:5000/internal/infer'
$env:INFERENCE_BIND_HOST = '127.0.0.1'
$env:PORT = '5000'
$env:VUE_APP_BASE_URL = 'http://127.0.0.1:8080'
$env:BROWSER = 'none'
$env:NODE_PATH = Join-Path $frontendDir 'node_modules\@vue\cli-service\node_modules'

if ([string]::IsNullOrWhiteSpace($env:INFERENCE_INTERNAL_TOKEN)) {
    $env:INFERENCE_INTERNAL_TOKEN = $env:SENSOR_INFERENCE_INTERNAL_TOKEN
}
if ([string]::IsNullOrWhiteSpace($env:SENSOR_INFERENCE_INTERNAL_TOKEN)) {
    $env:SENSOR_INFERENCE_INTERNAL_TOKEN = $env:INFERENCE_INTERNAL_TOKEN
}
if ([string]::IsNullOrWhiteSpace($env:INFERENCE_INTERNAL_TOKEN) -or
    $env:INFERENCE_INTERNAL_TOKEN.Length -lt 32) {
    throw '.env 中必须设置至少 32 字符的 SENSOR_INFERENCE_INTERNAL_TOKEN'
}

Write-Step '环境和本地资源预检'
$mavenExe = Assert-Command -Name 'mvn'
$javaExe = Assert-Command -Name 'java'
$npmExe = Assert-Command -Name 'npm.cmd'
$cmdExe = Assert-Command -Name 'cmd.exe'
if (-not (Test-Path -LiteralPath $pythonExe)) { throw "缺少项目 Python：$pythonExe" }
if (-not (Test-Path -LiteralPath $iotdbConfigNodeScript) -or
    -not (Test-Path -LiteralPath $iotdbDataNodeScript)) {
    throw "IoTDB 启动脚本不完整：$iotdbWindowsSbin。请在 .env 中设置正确的 IOTDB_HOME。"
}
$env:IOTDB_HOME = $script:iotdbHome
$env:CONFIGNODE_HOME = $script:iotdbHome
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaBin = Split-Path -Parent $javaExe
    $env:JAVA_HOME = Split-Path -Parent $javaBin
}
if (-not (Test-Path -LiteralPath (Join-Path $frontendDir 'node_modules'))) {
    throw '缺少 ruoyi-ui/node_modules，请先在 ruoyi-ui 目录执行 npm ci'
}
& $pythonExe -c "import fastapi, uvicorn, torch, numpy, scipy" 2>$null
if ($LASTEXITCODE -ne 0) { throw '项目 .venv 缺少推理依赖，请按 requirements.txt 安装' }

$manifestPath = Join-Path $inferenceDir 'models-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "模型清单不存在：$manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
foreach ($model in $manifest.models) {
    $artifactPath = Join-Path $modelRoot $model.artifact
    if (-not (Test-Path -LiteralPath $artifactPath)) { throw "模型制品不存在：$artifactPath" }
    $actualHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne ([string]$model.sha256).ToLowerInvariant()) {
        throw "模型 SHA-256 不匹配：$artifactPath"
    }
    [Environment]::SetEnvironmentVariable([string]$model.environmentPath, $artifactPath, 'Process')
    [Environment]::SetEnvironmentVariable([string]$model.environmentSha256, $actualHash, 'Process')
    $versionName = if ($model.type -eq 'bearing') { 'BEARING_MODEL_VERSION' } else { 'GEAR_MODEL_VERSION' }
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($versionName, 'Process'))) {
        [Environment]::SetEnvironmentVariable($versionName, [string]$model.version, 'Process')
    }
    Write-Host "[OK] model=$($model.type) hash=$($actualHash.Substring(0, 12))..." -ForegroundColor Green
}

if (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port 3306)) {
    throw 'MySQL(127.0.0.1:3306) 未启动，后端无法连接业务数据库'
}
if (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port 6379)) {
    throw 'Redis(127.0.0.1:6379) 未启动，后端无法完成登录和缓存初始化'
}
Write-Step '停止当前项目的旧监听进程'
# 5001/9528 是历史架构遗留端口；仅在确认属于本项目时才清理。
foreach ($legacyPort in @(5001, 9528)) {
    $legacyOwner = Get-PortProcessId -Port $legacyPort
    if ($legacyOwner -and (Test-IsProjectProcess -Id $legacyOwner)) {
        Stop-Listener -Port $legacyPort
    }
}
foreach ($portToStop in @($FrontendPort, 5000, 6667, 8080, 8888, 8890, 8891, 9000, 10710)) {
    Stop-Listener -Port $portToStop
}

if (-not $SkipBuild) {
    Write-Step '同步构建 Java 多模块工程'
    Push-Location $projectRoot
    try {
        & $mavenExe '-DskipTests' 'install'
        if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败，退出码：$LASTEXITCODE" }
    } finally {
        Pop-Location
    }
} else {
    Write-Host '[SKIP] 已跳过 Java 构建' -ForegroundColor Yellow
}
if (-not (Test-Path -LiteralPath $adminJar)) { throw "后端 JAR 不存在：$adminJar" }

$started = @()
try {
    Write-Step '启动 Apache IoTDB ConfigNode'
    $iotdbConfigNode = Start-LoggedProcess -Name 'iotdb-confignode' -FilePath $cmdExe `
        -ArgumentList @('/d', '/c', "`"$iotdbConfigNodeScript`" -f") `
        -WorkingDirectory $script:iotdbHome
    $started += $iotdbConfigNode
    $configDeadline = (Get-Date).AddSeconds(90)
    while (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port 10710) -and
        (Get-Date) -lt $configDeadline) {
        Start-Sleep -Seconds 1
    }
    if (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port 10710)) {
        throw 'IoTDB ConfigNode 在 90s 内未就绪（127.0.0.1:10710）'
    }
    Write-Host '[READY] IoTDB ConfigNode -> 127.0.0.1:10710' -ForegroundColor Green

    Write-Step '启动 Apache IoTDB DataNode'
    $iotdbDataNode = Start-LoggedProcess -Name 'iotdb-datanode' -FilePath $cmdExe `
        -ArgumentList @('/d', '/c', "`"$iotdbDataNodeScript`" -f") `
        -WorkingDirectory $script:iotdbHome
    $started += $iotdbDataNode
    $dataDeadline = (Get-Date).AddSeconds(120)
    while (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port 6667) -and
        (Get-Date) -lt $dataDeadline) {
        Start-Sleep -Seconds 1
    }
    if (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port 6667)) {
        throw 'IoTDB DataNode 在 120s 内未就绪（127.0.0.1:6667）'
    }
    Write-Host '[READY] IoTDB DataNode -> 127.0.0.1:6667' -ForegroundColor Green

    Write-Step '启动统一 Python 推理服务'
    $inference = Start-LoggedProcess -Name 'inference' -FilePath $pythonExe `
        -ArgumentList @('inference_service.py') -WorkingDirectory $inferenceDir
    $started += $inference
    Wait-HttpReady -Name '统一推理服务' -Url 'http://127.0.0.1:5000/internal/health/ready' `
        -Headers @{ 'X-Internal-Token' = $env:INFERENCE_INTERNAL_TOKEN } -TimeoutSeconds 90
    # Windows 环境变量名不区分大小写；Python 启动后清除 PORT，避免覆盖 Vue 端口。
    [Environment]::SetEnvironmentVariable('PORT', $null, 'Process')

    Write-Step '启动 Spring Boot 平台'
    $backend = Start-LoggedProcess -Name 'backend' -FilePath $javaExe `
        -ArgumentList @('-jar', $adminJar, '--spring.profiles.active=dev') -WorkingDirectory $projectRoot
    $started += $backend
    Wait-HttpReady -Name 'Spring Boot 后端' -Url 'http://127.0.0.1:8080/captchaImage' -TimeoutSeconds 120

    Write-Step '启动 Vue 开发服务器'
    $frontend = Start-LoggedProcess -Name 'frontend' -FilePath $npmExe `
        -ArgumentList @('run', 'dev', '--', '--port', [string]$FrontendPort) -WorkingDirectory $frontendDir
    $started += $frontend
    Wait-HttpReady -Name 'Vue 前端' -Url "http://127.0.0.1:$FrontendPort/" -TimeoutSeconds 120
    Wait-HttpReady -Name '诊断测点总览' `
        -Url "http://127.0.0.1:$FrontendPort$diagnosisOverviewPath" -TimeoutSeconds 30

    # npm.cmd 与 Python venv 启动器可能会派生真正的服务进程，因此记录实际监听 PID，
    # 避免后续停止或排障时拿到已经退出的包装进程。
    $pidState = @{
        frontend = Get-PortProcessId -Port $FrontendPort
        backend = Get-PortProcessId -Port 8080
        inference = Get-PortProcessId -Port 5000
        iotdbConfigNode = Get-PortProcessId -Port 10710
        iotdbDataNode = Get-PortProcessId -Port 6667
        matReceiver = Get-PortProcessId -Port 8888
        tcpCollector = Get-PortProcessId -Port 8890
        tcpCollectorLegacy = Get-PortProcessId -Port 8891
    }
    $pidState['startedAt'] = (Get-Date).ToString('s')
    $pidState['projectRoot'] = $projectRoot
    $pidState | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding UTF8

    Write-Host ''
    Write-Host '========================================' -ForegroundColor Green
    Write-Host '  当前架构全部启动并通过就绪检查' -ForegroundColor Green
    Write-Host '========================================' -ForegroundColor Green
    Write-Host "  前端:      http://localhost:$FrontendPort" -ForegroundColor White
    Write-Host "  测点总览:  http://localhost:$FrontendPort$diagnosisOverviewPath" -ForegroundColor White
    Write-Host '  后端:      http://localhost:8080' -ForegroundColor White
    Write-Host '  统一推理:  http://127.0.0.1:5000/internal/*' -ForegroundColor White
    Write-Host '  IoTDB RPC: 127.0.0.1:6667' -ForegroundColor White
    Write-Host '  IoTDB CN:  127.0.0.1:10710' -ForegroundColor White
    Write-Host "  日志:      $logRoot" -ForegroundColor DarkCyan
    Write-Host "  PID 状态:  $pidFile" -ForegroundColor DarkCyan
    if (Get-PortProcessId -Port 8888) { Write-Host '  MAT接收:   127.0.0.1:8888' -ForegroundColor DarkGray }
    if (Get-PortProcessId -Port 8890) { Write-Host '  TCP采集:   127.0.0.1:8890' -ForegroundColor DarkGray }
    if (Get-PortProcessId -Port 8891) { Write-Host '  通道采集:  127.0.0.1:8891' -ForegroundColor DarkGray }
} catch {
    Write-Host "`n启动失败：$($_.Exception.Message)" -ForegroundColor Red
    foreach ($service in $started) {
        Show-LogTail -Path $service.stderr
        Show-LogTail -Path $service.stdout
        $startedProcess = $service.process
        if ($startedProcess -and -not $startedProcess.HasExited) {
            Stop-Process -Id $startedProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }
    foreach ($cleanupPort in @($FrontendPort, 5000, 6667, 8080, 8888, 8890, 8891, 9000, 10710)) {
        $cleanupOwner = Get-PortProcessId -Port $cleanupPort
        if ($cleanupOwner -and (Test-IsProjectProcess -Id $cleanupOwner)) {
            Stop-Process -Id $cleanupOwner -Force -ErrorAction SilentlyContinue
        }
    }
    throw
}
