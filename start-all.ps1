# =============================================================================
# start-all.ps1 - 当前开发架构的一键启动器
#
# 架构：Vue(80) -> Spring Boot(8080) -> FastAPI 统一推理(5000)
#                         -> Apache IoTDB ConfigNode/DataNode(10710/6667)
#       MAT 接收端口 8888 由 Spring Boot 内置服务启动。
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
$venvRoot = Join-Path $projectRoot '.venv'
$pythonExe = Join-Path $venvRoot 'Scripts\python.exe'
$pythonRequirements = Join-Path $inferenceDir 'requirements.txt'
$defaultIotdbHome = 'C:\iotdb\apache-iotdb-2.0.8-all-bin'
$modelRoot = Join-Path $projectRoot '.local-models'
$dataRoot = Join-Path $projectRoot '.local-data'
$attachmentRoot = Join-Path $dataRoot 'attachments'
$uploadRoot = Join-Path $dataRoot 'uploadPath'
$logRoot = Join-Path $dataRoot 'logs'
$runRoot = Join-Path $dataRoot 'run'
$pidFile = Join-Path $runRoot 'service-pids.json'
$mavenRepoRoot = Join-Path $dataRoot 'm2-repository'
$mavenSettingsPath = Join-Path $runRoot 'maven-settings.xml'
$localPythonExe = Join-Path $dataRoot 'python311\python.exe'
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

function Ensure-PythonEnvironment {
    param([Parameter(Mandatory = $true)][string]$RequirementsPath)

    $venvUsable = $false
    if (Test-Path -LiteralPath $pythonExe) {
        & $pythonExe -c "import sys; raise SystemExit(0 if (3, 11) <= sys.version_info[:2] < (3, 14) else 1)" 2>$null
        $venvUsable = $LASTEXITCODE -eq 0
    }

    if (-not $venvUsable) {
        $bootstrap = $null
        $bootstrapIsLauncher = $false
        $launcherVersion = $null
        if (-not [string]::IsNullOrWhiteSpace($env:PYTHON_EXE) -and
            (Test-Path -LiteralPath $env:PYTHON_EXE)) {
            $bootstrap = Get-Item -LiteralPath $env:PYTHON_EXE
        } elseif (Test-Path -LiteralPath $localPythonExe) {
            $bootstrap = Get-Item -LiteralPath $localPythonExe
        } else {
            $launcher = Get-Command py.exe -ErrorAction SilentlyContinue
            if ($launcher) {
                $launcherPath = if ($launcher.PSObject.Properties.Name -contains 'Source') {
                    $launcher.Source
                } else {
                    $launcher.FullName
                }
                & $launcherPath '-3.11' '--version' 2>$null
                if ($LASTEXITCODE -eq 0) {
                    $bootstrap = $launcher
                    $bootstrapIsLauncher = $true
                    $launcherVersion = '-3.11'
                }
            } else {
                $pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
                if ($pythonCommand) { $bootstrap = $pythonCommand }
            }
        }

        if ($null -eq $bootstrap) {
            throw '未检测到兼容的 Python 3.11。请安装 Python 3.11，或设置 PYTHON_EXE 指向可用的 python.exe。'
        }

        $environmentAction = if (Test-Path -LiteralPath $venvRoot) { '重建' } else { '创建' }
        Write-Step "$environmentAction Python 虚拟环境"
        $venvArguments = if ($bootstrapIsLauncher) {
            @($launcherVersion, '-m', 'venv')
        } else {
            @('-m', 'venv')
        }
        if (Test-Path -LiteralPath $venvRoot) { $venvArguments += '--clear' }
        $venvArguments += $venvRoot
        $bootstrapPath = if ($bootstrap.PSObject.Properties.Name -contains 'Source') {
            $bootstrap.Source
        } else {
            $bootstrap.FullName
        }
        & $bootstrapPath @venvArguments
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $pythonExe)) {
            throw "无法创建 Python 虚拟环境：$venvRoot。请安装 Python 3.11，或设置 PYTHON_EXE 指向 python.exe。"
        }
    }

    & $pythonExe -c "import sys; raise SystemExit(0 if (3, 11) <= sys.version_info[:2] < (3, 14) else 1)" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "项目需要 Python 3.11：$pythonExe。请安装或指定兼容版本后重试。"
    }

    if (-not (Test-Path -LiteralPath $RequirementsPath)) {
        throw "Python 依赖清单不存在：$RequirementsPath"
    }

    & $pythonExe -c "import fastapi, uvicorn, torch, numpy, scipy" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Step '安装 Python 推理依赖'
        & $pythonExe -m pip install --disable-pip-version-check -r $RequirementsPath
        if ($LASTEXITCODE -ne 0) {
            throw "Python 依赖安装失败，请检查网络或手动执行：$pythonExe -m pip install -r $RequirementsPath"
        }
    }
}

function Ensure-FrontendDependencies {
    $packageLock = Join-Path $frontendDir 'package-lock.json'
    $cliEntry = Join-Path $frontendDir 'node_modules\@vue\cli-service\bin\vue-cli-service.js'
    if (-not (Test-Path -LiteralPath $packageLock)) {
        throw "前端依赖清单不存在：$packageLock"
    }
    if (-not (Test-Path -LiteralPath $cliEntry)) {
        Write-Step '安装前端依赖'
        Push-Location $frontendDir
        try {
            & $npmExe 'ci'
            if ($LASTEXITCODE -ne 0) {
                throw 'npm ci 失败，请检查 Node.js/npm 版本和网络连接'
            }
        } finally {
            Pop-Location
        }
    }
}

function Ensure-MavenSettings {
    param([Parameter(Mandatory = $true)][string]$MavenExecutable)

    New-Item -ItemType Directory -Path $mavenRepoRoot -Force | Out-Null
    $globalSettings = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MavenExecutable) '..\conf\settings.xml'))
    try {
        if (Test-Path -LiteralPath $globalSettings) {
            [xml]$settings = Get-Content -LiteralPath $globalSettings -Raw
            $namespace = New-Object System.Xml.XmlNamespaceManager($settings.NameTable)
            $namespace.AddNamespace('m', $settings.settings.NamespaceURI)
            $repositoryNode = $settings.SelectSingleNode('/m:settings/m:localRepository', $namespace)
            if ($null -eq $repositoryNode) {
                $repositoryNode = $settings.CreateElement('localRepository', $settings.settings.NamespaceURI)
                [void]$settings.settings.InsertBefore($repositoryNode, $settings.settings.FirstChild)
            }
            $repositoryNode.InnerText = $mavenRepoRoot
            $settings.Save($mavenSettingsPath)
        } else {
            throw "Maven 全局 settings.xml 不存在：$globalSettings"
        }
    } catch {
        $repoXml = [Security.SecurityElement]::Escape($mavenRepoRoot)
        $fallback = @"
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <localRepository>$repoXml</localRepository>
</settings>
"@
        Set-Content -LiteralPath $mavenSettingsPath -Value $fallback -Encoding UTF8
    }
    return $mavenSettingsPath
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
    if (Test-Path -LiteralPath $pidFile) {
        try {
            $state = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
            foreach ($property in $state.PSObject.Properties) {
                $recordedPid = 0
                if ([int]::TryParse([string]$property.Value, [ref]$recordedPid) -and
                    $recordedPid -eq $Id) {
                    return $true
                }
            }
        } catch {
            # Ignore a stale or partially written PID file and inspect the process command line below.
        }
    }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$Id" -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $false }
    $commandLine = [string]$process.CommandLine
    if ($commandLine.IndexOf($projectRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    if (-not [string]::IsNullOrWhiteSpace($script:iotdbHome) -and
        $commandLine.IndexOf($script:iotdbHome, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    return $false
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

function Start-PortableRedis {
    param([Parameter(Mandatory = $true)][int]$Port)

    $portableRoot = Join-Path $dataRoot 'redis8'
    $server = Get-ChildItem -LiteralPath $portableRoot -Filter 'redis-server.exe' -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $server) { return $false }
    if (Test-TcpEndpoint -HostName '127.0.0.1' -Port $Port) { return $true }

    $runtimeRoot = $server.Directory.FullName
    $redisLog = Join-Path $logRoot 'redis-portable.out.log'
    $arguments = @('--port', [string]$Port, '--bind', '127.0.0.1', '--protected-mode', 'yes',
        '--save', '', '--appendonly', 'no', '--logfile', $redisLog)
    $process = Start-Process -FilePath $server.FullName -ArgumentList $arguments `
        -WorkingDirectory $runtimeRoot -WindowStyle Hidden -PassThru
    Write-Host "[START] Redis portable pid=$($process.Id) port=$Port" -ForegroundColor DarkCyan

    $deadline = (Get-Date).AddSeconds(15)
    while (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port $Port) -and (Get-Date) -lt $deadline) {
        if ($process.HasExited) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port $Port)) {
        throw "项目内置 Redis portable 未能监听 127.0.0.1:$Port，请查看 $redisLog"
    }
    return $true
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Invoke-ElevatedServiceStart {
    param(
        [Parameter(Mandatory = $true)][string]$DisplayName,
        [Parameter(Mandatory = $true)][string]$ServiceName
    )

    $shellPath = (Get-Process -Id $PID -ErrorAction Stop).Path
    $escapedServiceName = $ServiceName.Replace("'", "''")
    $command = @"
`$ErrorActionPreference = 'Stop'
Start-Service -Name '$escapedServiceName' -ErrorAction Stop
"@
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))

    Write-Host "[UAC] $DisplayName 服务 $ServiceName 需要管理员权限，正在请求授权" -ForegroundColor Yellow
    try {
        $elevatedProcess = Start-Process -FilePath $shellPath -Verb RunAs -ArgumentList @(
            '-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', $encodedCommand
        ) -Wait -PassThru -ErrorAction Stop
    } catch {
        throw "未能获取管理员权限来启动 $DisplayName 服务 $ServiceName。请接受 UAC 提示，或先在管理员 PowerShell 中执行 Start-Service -Name '$ServiceName'；原始错误：$($_.Exception.Message)"
    }

    if ($elevatedProcess.ExitCode -ne 0) {
        throw "管理员启动命令未能启动 $DisplayName 服务 $ServiceName（退出码：$($elevatedProcess.ExitCode)）。这通常是服务自身配置或数据文件错误；请检查 Windows 事件查看器和该服务的错误日志。"
    }
}

Import-LocalEnvironment -Path (Join-Path $projectRoot '.env')

$iotdbHomeCandidate = if ([string]::IsNullOrWhiteSpace($env:IOTDB_HOME)) {
    $defaultIotdbHome
} else {
    $env:IOTDB_HOME
}

function Start-DependencyService {
    param(
        [Parameter(Mandatory = $true)][string]$DisplayName,
        [Parameter(Mandatory = $true)][string[]]$CandidateNames,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSeconds = 30
    )

    if (Test-TcpEndpoint -HostName '127.0.0.1' -Port $Port) {
        Write-Host "[READY] $DisplayName -> 127.0.0.1:$Port (已在运行)" -ForegroundColor Green
        return
    }

    $service = $null
    foreach ($candidateName in ($CandidateNames | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
        $service = Get-Service -Name $candidateName -ErrorAction SilentlyContinue
        if ($service) { break }
    }
    if ($null -eq $service) {
        $names = ($CandidateNames | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join ', '
        throw "未找到 $DisplayName Windows 服务（尝试过：$names）。请安装服务或在 .env 中设置对应服务名。"
    }
    $resolvedServiceName = $service.Name

    if ($service.Status -eq 'StopPending') {
        Write-Host "[WAIT] $DisplayName service=$($service.Name) 正在停止，等待状态稳定" -ForegroundColor DarkGray
        $stopDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
        while ($service.Status -eq 'StopPending' -and (Get-Date) -lt $stopDeadline) {
            Start-Sleep -Milliseconds 500
            $service.Refresh()
        }
        if ($service.Status -eq 'StopPending') {
            throw "$DisplayName 服务 $($service.Name) 在 ${TimeoutSeconds}s 内未能停止，请稍后重试。"
        }
    }

    if ($service.Status -in @('StartPending', 'ContinuePending', 'Running')) {
        Write-Host "[WAIT] $DisplayName service=$($service.Name) 状态为 $($service.Status)，等待端口就绪" -ForegroundColor DarkGray
    } elseif ($service.Status -eq 'Stopped') {
        if ($service.StartType -eq 'Disabled') {
            throw "$DisplayName 服务 $($service.Name) 已被禁用。请先由管理员启用该服务；脚本不会自动修改服务启动类型。"
        }
        Write-Host "[START] $DisplayName service=$($service.Name)" -ForegroundColor DarkCyan
        try {
            Start-Service -Name $service.Name -ErrorAction Stop
        } catch {
            $directStartError = $_.Exception.Message
            $service = Get-Service -Name $resolvedServiceName -ErrorAction SilentlyContinue
            if ($service -and $service.Status -in @('StartPending', 'ContinuePending', 'Running')) {
                Write-Host "[WAIT] $DisplayName service=$($service.Name) 已由其他进程启动，等待端口就绪" -ForegroundColor DarkGray
            } elseif (Test-IsAdministrator) {
                throw "已使用管理员权限，但无法启动 $DisplayName 服务 $resolvedServiceName。请检查 Windows 事件查看器和该服务的错误日志；原始错误：$directStartError"
            } else {
                Invoke-ElevatedServiceStart -DisplayName $DisplayName -ServiceName $resolvedServiceName
            }
        }
    } else {
        throw "$DisplayName 服务 $($service.Name) 当前状态为 $($service.Status)，无法自动启动。请先将服务恢复为 Running 或 Stopped 后重试。"
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port $Port) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
    }
    if (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port $Port)) {
        $latestService = Get-Service -Name $resolvedServiceName -ErrorAction SilentlyContinue
        $latestStatus = if ($latestService) { [string]$latestService.Status } else { 'Unknown' }
        throw "$DisplayName 服务 $resolvedServiceName 启动后，127.0.0.1:$Port 在 ${TimeoutSeconds}s 内未就绪（当前服务状态：$latestStatus）。请检查 Windows 事件查看器和该服务的错误日志。"
    }
    Write-Host "[READY] $DisplayName -> 127.0.0.1:$Port" -ForegroundColor Green
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
# 一键启动使用 HTTP 本地开发服务器；Secure Cookie 只适用于 HTTPS，
# 否则浏览器不会回传 RUOYI_SESSION，登录后 /getInfo 会立即返回 401。
$env:SESSION_COOKIE_SECURE = 'false'
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
Ensure-PythonEnvironment -RequirementsPath $pythonRequirements
Ensure-FrontendDependencies
$mavenSettingsPath = Ensure-MavenSettings -MavenExecutable $mavenExe
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

$mysqlServiceCandidates = @('MySQL80', 'MySQL96', 'MySQL', 'mysqld')
$redisServiceCandidates = @('Redis', 'Memurai')
if (-not [string]::IsNullOrWhiteSpace($env:MYSQL_SERVICE_NAME)) {
    $mysqlServiceCandidates = @([string]$env:MYSQL_SERVICE_NAME) + $mysqlServiceCandidates
}
if (-not [string]::IsNullOrWhiteSpace($env:REDIS_SERVICE_NAME)) {
    $redisServiceCandidates = @([string]$env:REDIS_SERVICE_NAME) + $redisServiceCandidates
}
Start-DependencyService -DisplayName 'MySQL' -CandidateNames $mysqlServiceCandidates -Port 3306
$redisPort = if ([string]::IsNullOrWhiteSpace($env:REDIS_PORT)) { 6379 } else { [int]$env:REDIS_PORT }
if (-not (Test-TcpEndpoint -HostName '127.0.0.1' -Port $redisPort)) {
    $redisReady = $false
    $redisServiceError = ''
    try {
        Start-DependencyService -DisplayName 'Redis' -CandidateNames $redisServiceCandidates -Port $redisPort
        $redisReady = $true
    } catch {
        $redisServiceError = $_.Exception.Message
    }
    if (-not $redisReady) {
        try {
            $redisReady = Start-PortableRedis -Port $redisPort
        } catch {
            $redisServiceError = "$redisServiceError；便携版 Redis：$($_.Exception.Message)"
        }
    }
    if (-not $redisReady) {
        throw "Redis 未就绪。服务启动错误：$redisServiceError"
    }
}
Write-Step '停止当前项目的旧监听进程'
# 5001/9528 是历史架构遗留端口；仅在确认属于本项目时才清理。
foreach ($legacyPort in @(5001, 9528)) {
    $legacyOwner = Get-PortProcessId -Port $legacyPort
    if ($legacyOwner -and (Test-IsProjectProcess -Id $legacyOwner)) {
        Stop-Listener -Port $legacyPort
    }
}
foreach ($portToStop in @($FrontendPort, 5000, 6667, 8080, 8888, 10710)) {
    Stop-Listener -Port $portToStop
}

if (-not $SkipBuild) {
    Write-Step '同步构建 Java 多模块工程'
    Push-Location $projectRoot
    try {
        # 清理旧 target 后再构建，避免增量编译残留导致启动时加载错误字节码。
        & $mavenExe '-s' $mavenSettingsPath '-DskipTests' 'clean' 'install'
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
    # The diagnosis page is permission/menu driven and may legitimately return
    # 404 before a user session has loaded dynamic routes. The root page above
    # is the reliable process-level readiness check.

    # npm.cmd 与 Python venv 启动器可能会派生真正的服务进程，因此记录实际监听 PID，
    # 避免后续停止或排障时拿到已经退出的包装进程。
    $pidState = @{
        frontend = Get-PortProcessId -Port $FrontendPort
        backend = Get-PortProcessId -Port 8080
        inference = Get-PortProcessId -Port 5000
        iotdbConfigNode = Get-PortProcessId -Port 10710
        iotdbDataNode = Get-PortProcessId -Port 6667
        matReceiver = Get-PortProcessId -Port 8888
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
    foreach ($cleanupPort in @($FrontendPort, 5000, 6667, 8080, 8888, 10710)) {
        $cleanupOwner = Get-PortProcessId -Port $cleanupPort
        if ($cleanupOwner -and (Test-IsProjectProcess -Id $cleanupOwner)) {
            Stop-Process -Id $cleanupOwner -Force -ErrorAction SilentlyContinue
        }
    }
    throw
}
