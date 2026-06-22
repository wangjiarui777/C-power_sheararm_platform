# RuoYi-Vue 传感器监测平台 — 部署指南 (for Claude Code)

> **使用说明**：在新 Windows 电脑上打开 Claude Code，将此文件拖入对话框或输入 `/init` 初始化项目后，
> 对 Claude Code 说：**"请按照 DEPLOY.md 中的步骤帮我部署这个项目"**

---

## 目标机器要求

- Windows 10 (1809+) 或 Windows 11
- 管理员权限（安装软件需要）
- 网络连接（下载依赖）

---

## 部署步骤（由 Claude Code 逐步执行）

### 阶段 1: 检查 & 安装基础环境

#### 1.1 检查 winget
```powershell
winget --version
```
如果不可用，打开 Microsoft Store 搜索"应用安装程序"更新。

#### 1.2 安装 JDK 17
```powershell
# 检查是否已安装
java --version

# 未安装则通过 winget 安装
winget install EclipseAdoptium.Temurin.17.JDK --accept-package-agreements --accept-source-agreements

# 验证（需要重新打开终端后）
java --version
# 期望输出: openjdk version "17.x.x"
```

#### 1.3 安装 Maven 3.9+
```powershell
mvn --version

# 未安装则:
winget install Apache.Maven.3 --accept-package-agreements --accept-source-agreements

# 配置阿里云镜像加速（编辑 %MAVEN_HOME%\conf\settings.xml）
# 在 <mirrors> 节点中添加:
# <mirror>
#     <id>aliyunmaven</id>
#     <mirrorOf>*</mirrorOf>
#     <name>阿里云公共仓库</name>
#     <url>https://maven.aliyun.com/repository/public</url>
# </mirror>
```

#### 1.4 安装 Node.js LTS
```powershell
node -v

# 未安装则:
winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements

# 配置 npm 镜像
npm config set registry https://registry.npmmirror.com
```

#### 1.5 安装 Python 3.10+
```powershell
python --version

# 未安装则:
winget install Python.Python.3.12 --accept-package-agreements --accept-source-agreements

# 创建项目虚拟环境并安装推理依赖
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

---

### 阶段 2: 安装数据库 & 中间件

#### 2.1 安装 MySQL 8.0
```powershell
mysql --version

# 未安装则:
winget install Oracle.MySQL.8.0 --accept-package-agreements --accept-source-agreements

# MySQL Installer 安装过程中:
#   - 选择 "Server only"
#   - root 密码设为: admin123
#   - 端口保持默认 3306
```

**安装完成后创建数据库并导入数据：**

使用 Bash 工具执行（注意：`-p` 和密码之间**没有空格**）：
```bash
MYSQL_PWD="admin123"

# 创建数据库
mysql -u root -p"$MYSQL_PWD" -e "CREATE DATABASE IF NOT EXISTS \`ry-yue\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

# 导入 SQL 文件（按顺序）
SQL_DIR="<项目根目录>/sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/ry_20260417.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/quartz.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/sensor_monitoring_module.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/vibration_analysis.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/vibration_data.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/temperature_data.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/enhanced_inference_record.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/phm_platform.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/industrial_monitoring_upgrade.sql"
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/cleanup_redundant_sidebar_menus.sql"
```

**PHM 表结构验证：**
```bash
mysql -u root -p"$MYSQL_PWD" ry-yue -e "SHOW TABLES LIKE 'phm_%';"
mysql -u root -p"$MYSQL_PWD" ry-yue -e "SELECT COUNT(*) AS device_count FROM phm_device; SELECT COUNT(*) AS rule_count FROM phm_alarm_rule;"
mysql -u root -p"$MYSQL_PWD" ry-yue -e "SELECT status, COUNT(*) AS alarm_count FROM phm_alarm_event GROUP BY status; SELECT COUNT(*) AS handle_count FROM phm_alarm_handle_record;"
mysql -u root -p"$MYSQL_PWD" ry-yue -e "SHOW TABLES LIKE 'enhanced_inference_record';"
```

其中 `enhanced_inference_record.sql` 用于 Python 推理服务写入诊断历史，`phm_platform.sql` 用于设备资产、测点、告警闭环、报表和系统配置，`industrial_monitoring_upgrade.sql` 用于统一测点、数据质量、告警流程状态和工业监测读模型。三者都需要导入，监测闭环才能完整运行。

#### 2.2 安装 Redis

**方案 A — Memurai（推荐，最接近原生）：**
```powershell
winget install Memurai.Memurai --accept-package-agreements --accept-source-agreements
```

**方案 B — Redis for Windows（旧版但稳定）：**
```powershell
# 手动下载
# https://github.com/tporadowski/redis/releases
# 下载 Redis-x64-5.0.14.1.msi 安装
```

**方案 C — WSL2 + Redis（最接近生产环境）：**
```powershell
wsl --install -d Ubuntu
# 进入 Ubuntu 后:
sudo apt update && sudo apt install redis-server
sudo service redis-server start
```

**验证：**
```powershell
redis-cli ping
# 应返回: PONG
```

#### 2.3 准备 Apache IoTDB 2.0.x 集群（可选，仅传感器时序功能需要）

> 如果仅需后台管理功能，可跳过此步骤。
> 跳过后在 `ruoyi-admin/src/main/resources/application-dev.yml` 中设置 `sensor.store-type: noop`

```powershell
# 1. 按官方文档部署 3 ConfigNode + 3 DataNode
# 2. 建议使用主机名，例如:
#    iotdb-cn-1, iotdb-cn-2, iotdb-cn-3
#    iotdb-dn-1, iotdb-dn-2, iotdb-dn-3
# 3. 推荐基线参数:
#    schema_replication_factor=3
#    data_replication_factor=2
#    timestamp_precision=us
# 4. 集群启动后，按 README-IOTDB.md 的命令使用 Table CLI 执行:
#    ruoyi-admin/src/main/resources/sql/iotdb-init.sql
#    该脚本可重复执行，不能使用 Tree CLI。
```

---

### 阶段 3: 修改项目配置

#### 3.1 检查并修改数据库连接
编辑 `ruoyi-admin/src/main/resources/application-dev.yml`：

```yaml
spring:
  datasource:
    druid:
      master:
        url: jdbc:mysql://localhost:3306/ry-yue?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8
        username: root
        password: admin123    # <-- 如果不是 admin123，改为你的密码
```

#### 3.2 检查 Redis 连接
同文件，确保 Redis 配置正确：
```yaml
spring:
  redis:
    host: localhost
    port: 6379
    database: 0
    timeout: 10s
    # password: yourpassword  # 如果 Memurai/Redis 设置了密码，取消注释
```

#### 3.3 检查 IoTDB 连接（如启用了时序存储）
```yaml
sensor:
  store-type: iotdb
  iotdb:
    enabled: true              # 如未部署 IoTDB，改为 false 或将 store-type 设为 noop
    database: monitoring
    node-urls: iotdb-dn-1:6667,iotdb-dn-2:6667,iotdb-dn-3:6667
    username: root
    password: root
    connection-timeout-ms: 3000
    wait-session-timeout-ms: 3000
    query-timeout-ms: 15000
    max-retry-count: 1
    retry-interval-ms: 500
    reconnect-interval-seconds: 30
    rpc-compression: true
    redirection: true
    auto-fetch-nodes: true
    use-ssl: false
    fetch-size: 512
    session-pool-size: 8
    ttl-days: 3650
    timestamp-precision: us
```

IoTDB 不可用时主应用仍会启动，但时序查询接口返回 HTTP 503，
`/sensor/monitoring/timeseries/health` 可查看连接状态、失败次数和最近成功写入时间。

#### 3.4 配置采集凭据与 WebSocket Origin

生产环境必须通过环境变量配置：

```powershell
$env:SENSOR_COLLECTOR_TOKEN = "replace-with-a-long-random-secret"
$env:SENSOR_WS_ALLOWED_ORIGINS = "https://monitor.example.com"
```

采集端调用 `/sensor/vibration-data/upload` 或 `/sensor/temperature-data/upload`
时携带 `X-Collector-Token`。浏览器 WebSocket 先调用受保护的
`POST /sensor/ws-ticket` 获取 60 秒一次性票据。

数据库升级时依次执行：

```text
sql/industrial_monitoring_upgrade.sql
sql/sensor_module_menu_migration.sql
```

两个脚本均可重复执行；业务菜单由 `sys_menu` 和角色授权管理。

#### 3.5 创建文件上传目录
```powershell
mkdir -Force D:\ruoyi\uploadPath
```

---

### 阶段 4: 构建项目

#### 4.1 构建后端
```bash
cd <项目根目录>

# 清理并构建所有模块（跳过测试）
mvn clean install -DskipTests
```

预计耗时 3-10 分钟（首次需下载依赖）。

#### 4.2 安装前端依赖
```bash
cd ruoyi-ui

# 安装依赖
npm install
```

---

### 阶段 5: 启动服务

#### 5.1 启动后端
```bash
cd <项目根目录>/ruoyi-admin

# 以开发模式启动
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

启动成功标志：
```
Started RuoYiApplication in XX.XXX seconds (process running for XX.XXX)
```

后端运行在 http://localhost:8080

#### 5.2 启动前端（新终端窗口）
```bash
cd <项目根目录>/ruoyi-ui

# 开发模式启动（默认端口 80）
npm run dev
```

前端运行在 http://localhost:80

> 如果端口 80 被占用，使用其他端口：
> ```bash
> npm run dev -- --port 8081
> ```

#### 5.3（可选）启动 Python 推理服务（新终端窗口）

PHM 诊断工具包按模型类型调用两个推理服务：
- 齿轮诊断：`http://127.0.0.1:5000`
- 轴承诊断：`http://127.0.0.1:5001`

**推理服务 Python 环境检查：**
启动脚本会优先使用项目根目录的 `.venv\Scripts\python.exe`，不要依赖裸 `python` 或 Miniconda 全局环境。

```powershell
cd <项目根目录>
.\.venv\Scripts\python.exe -c "import sys, torch; print(sys.executable); print(torch.__version__)"
```

如果提示缺少 `torch`，执行：
```powershell
.\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt
```

推荐直接使用项目根目录的一键脚本：
```powershell
cd <项目根目录>
.\run-all.ps1
```

或分别启动：
```powershell
cd <项目根目录>
.\run-gear.ps1
.\run-bearing.ps1
```

如需自定义端口，需要同步修改前端环境变量：
```env
VUE_APP_INFERENCE_SERVICE_URL=http://127.0.0.1:5000
VUE_APP_BEARING_SERVICE_URL=http://127.0.0.1:5001
```

#### 5.4（可选）启动 Java 数据接收器（新终端窗口）
```bash
cd <项目根目录>/ruoyi-sensor/inference/get
javac CwruMatReceiver.java
java CwruMatReceiver 8888 got
```

---

### 阶段 6: 验证

1. **后端健康检查**：浏览器访问 http://localhost:8080
2. **前端页面**：浏览器访问 http://localhost:80
3. **登录系统**：使用默认管理员账号
   - 用户名：`admin`
   - 密码：`admin123`
4. **Druid 监控**：http://localhost:8080/druid（用户名 `ruoyi`，密码 `123456`）
5. **Swagger 文档**：http://localhost:8080/swagger-ui/index.html
6. **齿轮推理健康**：http://localhost:5000/health
7. **轴承推理健康**：http://localhost:5001/health
8. **PHM 表结构**：执行第 2.1 节的 `SHOW TABLES LIKE 'phm_%';` 验证命令

### PHM 业务闭环验收

完成基础验证后，按下面流程确认平台功能已串起来：

**接口冒烟验证（推荐先跑）：**
```powershell
cd <项目根目录>

# 如果已关闭验证码，可直接用账号密码登录并验证
.\setup\phm-smoke-test.ps1 -BaseUrl http://localhost:8080

# 如果验证码开启，先登录前端，在浏览器 Cookie 中复制 Admin-Token，再传入脚本
.\setup\phm-smoke-test.ps1 -BaseUrl http://localhost:8080 -Token "<Admin-Token>"

# 额外验证新增/删除大事记、服务报告等写接口（脚本会清理自己创建的数据）
.\setup\phm-smoke-test.ps1 -BaseUrl http://localhost:8080 -Token "<Admin-Token>" -Mutating
```

脚本会检查设备集群、机器大脑、测点/特征值/告警规则、告警详情、实时报表、历史报表、服务报告和系统配置接口；导入 `phm_platform.sql` 后还会校验 `ALM202605100001` 的已处理状态和处置记录。

1. 打开 `http://localhost/phm/cluster`，确认能看到设备集群、运行率、健康状态、列表/卡片视图切换和关注设备。
2. 点击 `一号主轴承试验台` 进入机器大脑，确认能看到电子铭牌、工况参数、测点卡片、趋势图、最新诊断、告警摘要和设备大事记入口。
3. 打开 `http://localhost/phm/alarms`，点击告警行，确认详情弹窗能展示告警基础信息、匹配规则、处理记录、关联诊断和设备大事记。导入 `phm_platform.sql` 后，`ALM202605100001` 应自带一条已处理记录。
4. 在告警中心对未处理告警执行“确认处理”或“忽略告警”，填写原因后确认状态变化，并在设备大事记中看到对应处置事件。
5. 打开 `http://localhost/phm/reports`，验证实时报表、历史运行报表导出，以及服务报告 PDF 上传、查看、下载、删除。
6. 打开 `http://localhost/phm/config`，验证设备、测点、告警规则、特征值、形貌图/附件和系统展示配置可以新增、编辑和保存。
7. 诊断联动验证：从机器大脑进入诊断页面，执行一次齿轮或轴承推理，确认结果带回设备/测点上下文，并在 PHM 告警或诊断记录中可追溯。

---

## 常见问题修复

### 问题 1: Maven 依赖下载慢或失败

在 `%MAVEN_HOME%\conf\settings.xml` 的 `<mirrors>` 中添加阿里云镜像（参考 1.3 节）。然后重新构建：
```bash
mvn clean install -DskipTests -U
```

### 问题 2: npm install 失败

```bash
# 清除缓存
npm cache clean --force

# 删除 node_modules 和 lock 文件
rm -rf node_modules package-lock.json

# 重新安装
npm install
```

### 问题 3: 8080 端口被占用

编辑 `ruoyi-admin/src/main/resources/application.yml`：
```yaml
server:
  port: 8081
```
同时修改 `ruoyi-ui/.env.development`：
```
VUE_APP_BASE_URL=http://localhost:8081
```

### 问题 4: 前端代理 502 错误

确保后端已先启动在 8080 端口上。

### 问题 5: 数据库连接拒绝

```powershell
# 检查 MySQL 服务状态
Get-Service MySQL*
# 如未运行则启动
Start-Service MySQL*
```

### 问题 6: Redis 连接失败

```powershell
# 检查 Redis 服务
Get-Service Redis*
Get-Service Memurai*
# 如未运行则启动
Start-Service Memurai*
```

### 问题 7: 验证码不显示

确保 Redis 正常运行。验证码使用 Redis 存储。

---

## 端口总览

| 服务 | 端口 | 协议 |
|------|------|------|
| Vue 前端 (dev) | 80 | HTTP |
| Spring Boot 后端 | 8080 | HTTP |
| MySQL | 3306 | TCP |
| Redis | 6379 | TCP |
| Apache IoTDB DataNode RPC | 6667 | TCP |
| Apache IoTDB ConfigNode | 10710 | TCP |
| Netty 传感器 | 9000 | TCP |
| CwruMatReceiver | 8888 | TCP |
| TCP 传感器 | 8890 | TCP |
| 通道帧 TCP | 8891 | TCP |
| 齿轮推理服务 | 5000 | HTTP |
| 轴承推理服务 | 5001 | HTTP |

---

## 环境变量速查（前端）

| 变量 | 开发环境值 | 生产环境值 |
|------|-----------|-----------|
| `VUE_APP_TITLE` | 振动温度监测平台 | 振动温度监测平台 |
| `VUE_APP_BASE_API` | `/dev-api` | `/prod-api` |
| `VUE_APP_BASE_URL` | `http://localhost:8080` | — |
| `VUE_APP_INFERENCE_SERVICE_URL` | `http://127.0.0.1:5000` | 按部署地址配置 |
| `VUE_APP_BEARING_SERVICE_URL` | `http://127.0.0.1:5001` | 按部署地址配置 |

---

## 注意

- 生产环境部署时，请务必修改 `application-dev.yml` 中的默认密码
- 生产环境建议使用 Nginx 部署前端 dist 文件，并配置反向代理
- 如果部署到云服务器，请配置安全组放行上述端口
- Apache IoTDB 集群和 Python 推理服务为非必需组件，可根据实际需求决定是否安装
