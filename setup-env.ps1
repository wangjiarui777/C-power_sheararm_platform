# =============================================================================
# setup-env.ps1 — RuoYi-Vue 传感器监测平台 一键环境配置脚本
# 适用系统: Windows 10 (1809+) / Windows 11
# 使用方法: 以管理员身份运行 PowerShell，执行：
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
#   .\setup-env.ps1
# =============================================================================

$ErrorActionPreference = 'Continue'
$projectRoot = $PSScriptRoot

# -----------------------------------------------------------------------------
# 配置变量（按需修改）
# -----------------------------------------------------------------------------
$MYSQL_ROOT_PASSWORD = 'admin123'
$MYSQL_DATABASE     = 'ry-yue'
$MYSQL_PORT         = 3306
$REDIS_PORT         = 6379
$TDENGINE_DB        = 'sensor_db'
$UPLOAD_PATH        = 'D:\ruoyi\uploadPath'

Write-Host @'
========================================
  RuoYi-Vue 传感器监测平台 — 环境自动安装
========================================
'@ -ForegroundColor Cyan

# -----------------------------------------------------------------------------
# 辅助函数
# -----------------------------------------------------------------------------
function Write-Step {
    param([string]$Message)
    Write-Host "`n>>> $Message" -ForegroundColor Yellow
}

function Write-OK {
    param([string]$Message)
    Write-Host "    [OK] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "    [WARN] $Message" -ForegroundColor Magenta
}

function Write-Skip {
    param([string]$Message)
    Write-Host "    [SKIP] $Message" -ForegroundColor DarkGray
}

function Test-Command {
    param([string]$Cmd, [string]$Args = '--version')
    try {
        $null = Get-Command $Cmd -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

# -----------------------------------------------------------------------------
# 1. 检查 winget 可用性
# -----------------------------------------------------------------------------
Write-Step '1/9 检查 winget (Windows Package Manager)'
$hasWinget = Test-Command 'winget'
if (-not $hasWinget) {
    Write-Warn 'winget 未安装，尝试通过 Microsoft Store 安装"应用安装程序"，或手动安装各组件'
    Write-Warn '详情: https://learn.microsoft.com/zh-cn/windows/package-manager/winget/'
} else {
    Write-OK 'winget 可用'
}

# -----------------------------------------------------------------------------
# 2. JDK 17 (Eclipse Adoptium)
# -----------------------------------------------------------------------------
Write-Step '2/9 安装 JDK 17 (Eclipse Adoptium)'

if (Test-Command 'java') {
    $javaVer = (& java -version 2>&1 | Select-Object -First 1)
    Write-Skip "Java 已安装: $javaVer"
} else {
    if ($hasWinget) {
        Write-Host '    正在通过 winget 安装...'
        winget install EclipseAdoptium.Temurin.17.JDK --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -eq 0) {
            Write-OK 'JDK 17 安装成功'
            Write-Warn '请关闭当前 PowerShell 并重新打开，或手动刷新环境变量使 java 生效'
        } else {
            Write-Warn 'winget 安装失败，请手动下载: https://adoptium.net/download/'
        }
    } else {
        Write-Warn '请手动下载 JDK 17: https://adoptium.net/download/'
    }
}

# -----------------------------------------------------------------------------
# 3. Maven
# -----------------------------------------------------------------------------
Write-Step '3/9 安装 Maven 3.9+'

if (Test-Command 'mvn') {
    $mvnVer = (& mvn -version 2>&1 | Select-Object -First 1)
    Write-Skip "Maven 已安装: $mvnVer"
} else {
    if ($hasWinget) {
        Write-Host '    正在通过 winget 安装...'
        winget install Apache.Maven.3 --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -eq 0) {
            Write-OK 'Maven 安装成功'
        } else {
            Write-Warn 'winget 安装失败，请手动下载: https://maven.apache.org/download.cgi'
        }
    } else {
        Write-Warn '请手动下载 Maven: https://maven.apache.org/download.cgi'
    }
}

# -----------------------------------------------------------------------------
# 4. Node.js (LTS)
# -----------------------------------------------------------------------------
Write-Step '4/9 安装 Node.js LTS'

if (Test-Command 'node') {
    $nodeVer = (& node -v 2>&1)
    Write-Skip "Node.js 已安装: $nodeVer"
} else {
    if ($hasWinget) {
        Write-Host '    正在通过 winget 安装 Node.js LTS...'
        winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -eq 0) {
            Write-OK 'Node.js LTS 安装成功'
            Write-Warn '请关闭当前 PowerShell 并重新打开使 node/npm 生效'
        } else {
            Write-Warn 'winget 安装失败，请手动下载: https://nodejs.org/zh-cn'
        }
    } else {
        Write-Warn '请手动下载 Node.js: https://nodejs.org/zh-cn'
    }
}

# 配置 npm 镜像
if (Test-Command 'npm') {
    Write-Host '    配置 npm 淘宝镜像...'
    npm config set registry https://registry.npmmirror.com 2>$null
    Write-OK 'npm 镜像已配置为 npmmirror.com'
}

# -----------------------------------------------------------------------------
# 5. MySQL
# -----------------------------------------------------------------------------
Write-Step '5/9 安装 MySQL 8.0'

if (Test-Command 'mysql') {
    $mysqlVer = (& mysql --version 2>&1)
    Write-Skip "MySQL 已安装: $mysqlVer"
} else {
    if ($hasWinget) {
        Write-Host '    正在通过 winget 安装 MySQL 8.0...'
        winget install Oracle.MySQL.8.0 --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -eq 0) {
            Write-OK 'MySQL 安装成功（请在 MySQL Installer 中完成配置，root 密码设置为 admin123）'
        } else {
            Write-Warn 'winget 安装失败，请手动下载: https://dev.mysql.com/downloads/mysql/'
        }
    } else {
        Write-Warn '请手动下载 MySQL: https://dev.mysql.com/downloads/mysql/'
    }
}

# 如果 MySQL 已可用，直接创建数据库
if (Test-Command 'mysql') {
    Write-Host '    创建数据库 ry-yue...'
    $env:MYSQL_PWD = $MYSQL_ROOT_PASSWORD
    $createResult = & mysql -u root -e "CREATE DATABASE IF NOT EXISTS \`$MYSQL_DATABASE\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-OK "数据库 '$MYSQL_DATABASE' 已就绪"

        # 导入 SQL 文件（使用 cmd /c 确保 source 命令正常工作）
        $sqlDir = Join-Path $projectRoot 'sql'
        if (Test-Path $sqlDir) {
            Write-Host '    导入初始化 SQL...'
            $sqlFiles = Get-ChildItem $sqlDir -Filter '*.sql' | Sort-Object Name
            foreach ($file in $sqlFiles) {
                Write-Host "      导入: $($file.Name)"
                # 使用 cmd 方式导入，避免 PowerShell 下 source 命令不兼容
                $cmdArg = "mysql -u root -p$MYSQL_ROOT_PASSWORD $MYSQL_DATABASE < `"$($file.FullName)`""
                cmd /c $cmdArg 2>&1 | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    Write-Warn "      导入 $($file.Name) 可能失败，请手动检查"
                }
            }
            Write-OK 'SQL 文件导入完成'
        }
    } else {
        Write-Warn "数据库创建失败: $createResult"
        Write-Warn "请手动执行 mysql -u root -p 后运行:"
        Write-Warn "  CREATE DATABASE \`ry-yue\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
        Write-Warn "  然后逐个导入 sql/ 目录下的 .sql 文件"
    }
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}

# -----------------------------------------------------------------------------
# 6. Redis
# -----------------------------------------------------------------------------
Write-Step '6/9 安装 Redis'

# 检查 Redis 是否运行
$redisRunning = $false
try {
    $null = & redis-cli ping 2>&1
    if ($LASTEXITCODE -eq 0) { $redisRunning = $true }
} catch { }

if ($redisRunning) {
    Write-Skip 'Redis 已运行'
} else {
    # 尝试用 winget 安装 Memurai（Windows 版 Redis 兼容替代）
    if ($hasWinget -and -not (Test-Command 'redis-server')) {
        Write-Host '    尝试通过 winget 安装 Memurai（Redis Windows 兼容版）...'
        winget install Memurai.Memurai --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -eq 0) {
            Write-OK 'Memurai (Redis) 安装成功'
        }
    }

    if (-not (Test-Command 'redis-server')) {
        Write-Warn 'Redis 未自动安装。三个方案供选择:'
        Write-Warn '  方案A: 手动下载 Memurai https://www.memurai.com/'
        Write-Warn '  方案B: 下载 Redis for Windows https://github.com/tporadowski/redis/releases'
        Write-Warn '  方案C: 使用 WSL2 + Docker（推荐用于生产环境）'
        Write-Warn '         wsl --install -d Ubuntu'
        Write-Warn '         sudo apt install redis-server && sudo service redis-server start'
    }
}

# -----------------------------------------------------------------------------
# 7. TDengine
# -----------------------------------------------------------------------------
Write-Step '7/9 安装 TDengine 3.x'

$taosInstalled = Test-Command 'taos'
if ($taosInstalled) {
    Write-Skip 'TDengine 已安装'
} else {
    Write-Warn 'TDengine 不在 winget 仓库中，需手动安装:'
    Write-Warn '  1. 访问 https://docs.tdengine.com/get-started/package/'
    Write-Warn '  2. 下载 TDengine 3.x Windows x64 安装包'
    Write-Warn '  3. 安装后启动服务: sc start taosd'
    Write-Warn '  4. 创建数据库: taos -s "CREATE DATABASE sensor_db KEEP 3650 DURATION 10 BUFFER 16;"'
    Write-Warn ''
    Write-Warn '  如果不需要传感器时序数据功能，可跳过此步骤，'
    Write-Warn '  之后在 application-dev.yml 中设置 sensor.tdengine.enabled: false'
}

# -----------------------------------------------------------------------------
# 8. Python 3.10+
# -----------------------------------------------------------------------------
Write-Step '8/9 安装 Python 3.10+'

if (Test-Command 'python') {
    $pyVer = (& python --version 2>&1)
    Write-Skip "Python 已安装: $pyVer"
} else {
    if ($hasWinget) {
        Write-Host '    正在通过 winget 安装 Python 3.12...'
        winget install Python.Python.3.12 --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -eq 0) {
            Write-OK 'Python 安装成功'
        } else {
            Write-Warn 'winget 安装失败，请手动下载: https://www.python.org/downloads/'
        }
    } else {
        Write-Warn '请手动下载 Python 3.10+: https://www.python.org/downloads/'
    }
}

# 安装 Python 依赖
if (Test-Command 'python') {
    $reqFile = Join-Path $projectRoot 'requirements.txt'
    if (Test-Path $reqFile) {
        Write-Host '    安装 Python 依赖 (requirements.txt)...'
        pip install -r $reqFile -i https://pypi.tuna.tsinghua.edu.cn/simple 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-OK 'Python 依赖安装完成'
        } else {
            Write-Warn '部分 Python 依赖安装失败，请手动执行: pip install -r requirements.txt'
        }
    }
}

# -----------------------------------------------------------------------------
# 9. 创建必要目录 & 最终检查
# -----------------------------------------------------------------------------
Write-Step '9/9 创建上传目录 & 环境检查'

if (-not (Test-Path $UPLOAD_PATH)) {
    New-Item -ItemType Directory -Path $UPLOAD_PATH -Force | Out-Null
    Write-OK "已创建上传目录: $UPLOAD_PATH"
} else {
    Write-Skip "上传目录已存在: $UPLOAD_PATH"
}

# -----------------------------------------------------------------------------
# 最终汇总
# -----------------------------------------------------------------------------
Write-Host @'

========================================
  环境安装完成！请逐项确认以下组件：
========================================
'@ -ForegroundColor Cyan

$checks = @(
    @{Name='JDK 17';       Cmd='java';      Args='--version';  Required=$true},
    @{Name='Maven';        Cmd='mvn';       Args='--version';  Required=$true},
    @{Name='Node.js';      Cmd='node';      Args='-v';         Required=$true},
    @{Name='npm';          Cmd='npm';       Args='-v';         Required=$true},
    @{Name='MySQL';        Cmd='mysql';     Args='--version';  Required=$true},
    @{Name='Redis';        Cmd='redis-cli'; Args='ping';       Required=$true},
    @{Name='Python';       Cmd='python';    Args='--version';  Required=$false},
    @{Name='TDengine';     Cmd='taos';      Args='-V';         Required=$false}
)

foreach ($c in $checks) {
    try {
        $result = & $c.Cmd $c.Args 2>&1 | Select-Object -First 1
        $icon = if ($c.Required) { '[必需]' } else { '[可选]' }
        Write-Host "  [OK] $icon $($c.Name): $result" -ForegroundColor Green
    } catch {
        $icon = if ($c.Required) { '[必需] 缺少！' } else { '[可选]' }
        Write-Host "  [MISS] $icon $($c.Name) 未安装或未加入 PATH" -ForegroundColor Red
    }
}

Write-Host @'

----------------------------------------
  后续步骤:
----------------------------------------
  1. 确保上述 [必需] 组件全部 [OK]
  2. 导入数据库:
     cd sql/
     mysql -u root -p ry-yue < ry_20260417.sql
     mysql -u root -p ry-yue < quartz.sql
     （依次导入所有 .sql 文件）
  3. 修改配置（如密码不同）:
     ruoyi-admin\src\main\resources\application-dev.yml
  4. 构建并启动:
     mvn clean install -DskipTests
     cd ruoyi-admin && mvn spring-boot:run -Dspring-boot.run.profiles=dev
  5. 启动前端:
     cd ruoyi-ui && npm install && npm run dev

  或使用一键启动:
     .\start-all.ps1

========================================
'@ -ForegroundColor Cyan

Read-Host '按 Enter 键退出'
