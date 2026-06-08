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

# 安装项目依赖
pip install -r ruoyi-sensor/inference/requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
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
mysql -u root -p"$MYSQL_PWD" ry-yue < "$SQL_DIR/cleanup_redundant_sidebar_menus.sql"
```

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

#### 2.3 安装 TDengine 3.x（可选，仅传感器功能需要）

> 如果仅需后台管理功能，可跳过此步骤。
> 跳过后在 `ruoyi-admin/src/main/resources/application-dev.yml` 中设置 `sensor.tdengine.enabled: false`

```powershell
# TDengine 需手动下载安装
# 1. 访问 https://docs.tdengine.com/get-started/package/
# 2. 下载 Windows x64 安装包并安装
# 3. 启动服务:
sc start taosd

# 4. 创建数据库:
taos -s "CREATE DATABASE sensor_db KEEP 3650 DURATION 10 BUFFER 16;"
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

#### 3.3 检查 TDengine 连接（如安装了）
```yaml
sensor:
  tdengine:
    enabled: true              # 如未安装 TDengine，改为 false
    url: jdbc:TAOS-RS://localhost:6041/sensor_db
    username: root
    password: taosdata
```

#### 3.4 创建文件上传目录
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
```bash
cd <项目根目录>/ruoyi-sensor/inference
python inference_service.py
```
推理服务运行在 http://localhost:5001

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
6. **Python 推理健康**：http://localhost:5001/health

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
| TDengine | 6041 | TCP |
| Netty 传感器 | 9000 | TCP |
| CwruMatReceiver | 8888 | TCP |
| TCP 传感器 | 8890 | TCP |
| 通道帧 TCP | 8891 | TCP |
| Python 推理服务 | 5000 | HTTP |

---

## 环境变量速查（前端）

| 变量 | 开发环境值 | 生产环境值 |
|------|-----------|-----------|
| `VUE_APP_TITLE` | 振动温度监测平台 | 振动温度监测平台 |
| `VUE_APP_BASE_API` | `/dev-api` | `/prod-api` |
| `VUE_APP_BASE_URL` | `http://localhost:8080` | — |

---

## 注意

- 生产环境部署时，请务必修改 `application-dev.yml` 中的默认密码
- 生产环境建议使用 Nginx 部署前端 dist 文件，并配置反向代理
- 如果部署到云服务器，请配置安全组放行上述端口
- TDengine 和 Python 推理服务为非必需组件，可根据实际需求决定是否安装
