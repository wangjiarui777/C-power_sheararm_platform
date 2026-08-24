# RuoYi-Vue-master（工业设备健康管理平台）

## 项目简介

本项目基于 **若依（RuoYi-Vue）前后端分离框架 3.9.2** 深度改造，面向 **工业设备健康管理（PHM）** 场景，提供设备/测点管理、振动与温度历史查询、齿轮/轴承智能诊断、告警事件报表、历史数据下载、IoTDB 时序存储与低代码工作台。下位机文件接入统一为 8888 端口的 CWRU_MAT_V2。

技术体系：**Spring Boot 3 + MyBatis/MyBatis-Plus + Spring WebSocket + Redis Stream + IoTDB + Vue 2 + FastAPI/PyTorch**。

系统以 **Java（Spring Boot）** 为统一边界：负责用户认证（Redis 会话 Cookie + CSRF）、权限、数据范围、MAT 文件接收、诊断编排与持久化；**Python（FastAPI）** 只做模型推理，仅对 Java 暴露内部接口，浏览器不可直接访问。

## 核心功能

### 1. 基础后台管理（RuoYi 原生能力）
- 用户、角色、菜单、部门、字典、参数等统一管理
- 登录认证（会话 Cookie + CSRF 双提交）、权限校验、动态菜单
- 操作日志、登录日志、在线用户、低代码工作台

### 2. 工业监测与采集
- 设备、测点、振动数据、温度数据管理
- Spring 内置 MAT 文件接收（8888/CWRU_MAT_V2），文件校验、隔离、台账和幂等
- WebSocket（`/ws/sensor`、`/ws/monitoring`）一次性票据握手与实时推送
- 八通道实时监控页面、实时监测工作台（资产树、KPI 条、趋势图）

### 3. PHM 健康管理与智能诊断
- 设备集群、健康总览、设备大脑（`/phm/*`）
- 告警规则、告警处理、设备事件、实时/历史报表与 CSV 导出
- 齿轮、轴承模型推理（FastAPI + PyTorch，模型清单 + SHA-256 校验）
- 单点诊断、批量诊断、诊断任务历史、历史数据下载
- 自动 MAT 诊断任务：按测点唯一主模型绑定，异步推理、结果持久化和 WebSocket 联动
- MySQL→IoTDB 诊断结果同步（outbox + 重试 + 租约），IoTDB 主读、MySQL 回退

### 4. 时序存储（IoTDB 2 Table 模型）
- `telemetry_metric`、`vibration_frame`、`diagnosis_result` 三张时序表（默认 TTL 1095/90/3650 天）
- `sensor.store-type` 支持 `iotdb` / `noop` 切换，带健康指示器与降级语义

### 5. 附件安全存储
- `/attachments` 独立 CRUD：随机存储键、所有者鉴权、类型/大小限制
- 可选 Windows Defender 病毒扫描（`MpCmdRun.exe`）
- 主动内容（html/htm/svg/swf）已移出上传白名单

### 6. 低代码工作台（V2）
- 独立数据库 schema（`LOWCODE_DB_URL`），与主库最小权限隔离
- 版本化元数据（草稿/校验/发布/回滚）、数据库预览与 DDL 预览
- 业务资源白名单（`lc_resource_allowlist`）、服务端表策略
- 连接器出站强制代理 + 地址/路径校验，SSRF 防护
- 运行时写操作默认关闭（`LOWCODE_RUNTIME_WRITE_ENABLED=false`）

### 7. 安全加固（2026-08 审计闭环）
- 认证由 JWT 迁移为 Redis 不透明会话 Cookie + CSRF 双提交
- 首次登录强制改密门禁（428），服务端密码策略 12–64 位
- 上传内容按类型与大小校验并隔离存储
- NumPy 推理加载 `allow_pickle=False`，拒绝恶意 object-array
- PyTorch `weights_only=True`；移除全信任 TLS 工具
- 生产独立管理端口（127.0.0.1:8081）仅暴露 health/prometheus
- 详细结论见 `SECURITY_AUDIT_REPORT_2026-08-13.md`、`SECURITY_REMEDIATION_TRACKER.md`、`SECURITY_EXCEPTIONS.md`

## 技术栈

### 后端
- **Java 25 / Spring Boot 3.5.16**
- MyBatis 3.0.4 / MyBatis-Plus 3.5.9 / HikariCP
- Spring Security（会话 Cookie + CSRF）
- Spring WebSocket、Spring AOP、Fastjson2
- Netty 4.1.118.Final、JTransforms 3.1、commons-math3
- Apache IoTDB Session 2.0.8、Redis Streams
- Flyway Java 迁移、Actuator health/prometheus
- Lombok、POI（导出）、PageHelper

### 前端（Vue 2）
- **Vue 2.7.16 / Vue Router 3.6.5 / Vuex 3.6.0**
- Element UI 2.15.14、ECharts 6.1.0、Axios 1.18.1
- Vue CLI 5.0.9（Webpack 5）、core-js 3、Playwright E2E
- 固定工业主题（`industrial-theme.scss`）

### 推理服务（Python）
- FastAPI 0.115.12 / Uvicorn 0.34.2
- NumPy 2.2.5 / SciPy 1.15.2 / PyTorch 2.6.0
- prometheus-client、python-multipart、websockets、pytest 8.3.5

## 系统架构

```text
前端页面（Vue 2，端口 80）
   │
   ├── REST API（/dev-api 或 /prod-api 代理）──► Spring Boot（8080）
   ├── WebSocket（/ws/sensor、/ws/monitoring，一次性票据）──► 实时推送
   │
   ▼
Spring Boot 应用（ruoyi-admin）
   ├── ruoyi-framework   安全/会话/CSRF/拦截器/配置
   ├── ruoyi-system      用户、角色、菜单、系统业务
   ├── ruoyi-sensor      采集、Stream、诊断、PHM、WebSocket、IoTDB
   ├── ruoyi-lowcode     低代码工作台（独立数据源）
   └── ruoyi-common      公共工具、统一返回、低代码 SPI
   │
   ├── MySQL（ry-yue，业务真相）＋ 低代码独立库（ry-lowcode）
   ├── Redis（会话、Stream、DLQ、去重）
   ├── IoTDB（时序投影，6667/10710）
   ├── 附件目录 / 模型目录（.local-models，SHA-256 校验）
   ├── FastAPI 统一开发推理服务（127.0.0.1:5000，/internal/*）
   ├── 生产齿轮推理 worker（127.0.0.1:5001，/internal/infer/batch）
   └── 生产轴承推理 worker（127.0.0.1:5002，/internal/infer/batch）
```

### 关键设计
- **统一边界**：Java 是唯一对外认证与数据边界，Python 只做推理
- **异步管道**：遥测/帧消息先入 Redis Stream，消费者落库、写 IoTDB、推送 WS，失败进 DLQ
- **双写一致**：诊断记录 MySQL 为准，IoTDB 为投影，outbox + 租约同步，读时可回退
- **安全默认**：会话 Cookie、CSRF、数据范围、附件所有权、低代码白名单、模型哈希
- **实时可靠性**：采集持久化链路与诊断链路隔离；Redis AOF、ACK、`XPENDING/XCLAIM`、截止时间和有限重试保证实时新鲜度

8888 接入协议、迁移和验收说明见 [`AI_PROJECT_OVERVIEW.md`](AI_PROJECT_OVERVIEW.md)。

## 主要模块说明

### `ruoyi-admin`
项目启动入口、Web 接口聚合层、Flyway 迁移（`src/main/java/db/migration`）。

### `ruoyi-framework`
安全认证与会话、CSRF 过滤器、改密门禁和通用配置。

### `ruoyi-system`
用户、角色、部门、菜单、字典等系统管理。

### `ruoyi-common`
通用工具、统一响应、异常、低代码 SPI（`LowCodeActionHandler` 等）。

### `ruoyi-lowcode`
低代码工作台（项目/版本/连接器/资源白名单，独立数据源）。

### `ruoyi-sensor`
工业业务核心：MAT TCP 接收、PHM 健康管理、诊断编排、历史查询、WebSocket 和 IoTDB 时序存储。

### `ruoyi-sensor/mock`
MAT V2 Python 测试发送器，负责生成/发送带完整协议头的 `.mat` 文件。

### `ruoyi-sensor/inference`
独立 FastAPI/PyTorch 推理服务：齿轮/轴承模型加载、`/internal/*` 内部接口、模型清单与 SHA-256 校验。本地开发环境统一运行在 5000，并启用 `POST /internal/infer/batch`。

## 快速启动（Windows 开发环境）

### 前置依赖
- JDK 25、Maven 3.8+、Node.js 24、npm 11、Python 3.11（推荐）
- MySQL（Windows 服务 `MySQL80`/`MySQL96` 等）、Redis 5+（Windows 推荐 Memurai 4+）
- Apache IoTDB 2.0.x（默认 `C:\iotdb\apache-iotdb-2.0.8-all-bin`）
- 模型文件放于 `.local-models/`（齿轮 `best_model_classwise_maha.pth`、轴承 `best_model.pth`，SHA 与 `models-manifest.json` 一致）

### 首次准备
1. 复制 `.env.example` 为 `.env` 并填写密码、令牌等变量；
2. 安装 Python 依赖：

```powershell
.\.venv\Scripts\python.exe -m pip install -r ruoyi-sensor\inference\requirements.txt
```

3. 安装前端依赖：

```powershell
Set-Location ruoyi-ui
npm ci
```

### 一键启动

```powershell
.\start-all.ps1
```

如果 MySQL/Redis Windows 服务尚未运行，脚本会仅针对服务启动操作弹出 UAC 授权提示；无需以管理员身份运行整个开发环境。若取消授权，需先在管理员 PowerShell 中启动对应服务后重试。

脚本按依赖顺序完成：环境/模型/SHA 校验 → MySQL/Redis 检查（含 Streams 能力）→ 旧进程清理 → Maven 构建 → 启动 IoTDB、统一 FastAPI（5000）、Spring Boot（8080，dev profile）、Vue（80）→ 就绪检查 → 写日志与 PID。

常用参数：

```powershell
.\start-all.ps1 -SkipBuild          # 跳过 Maven 构建（复用已有 jar）
.\start-all.ps1 -ForcePortCleanup   # 强制终止占用目标端口的进程
.\start-all.ps1 -FrontendPort 8088  # 修改前端端口
```

启动后访问 `http://localhost:80`（测点总览：`/analysis-toolkit/bearing-diagnosis`）。
也可在 `ruoyi-ui` 下单独执行 `npm run dev`，或用 `bin/run.bat` 单独启动后端。

## 本地目录约定

```text
RuoYi-Vue-master
├── ruoyi-admin / ruoyi-common / ruoyi-framework
├── ruoyi-lowcode / ruoyi-system / ruoyi-sensor
│   ├── sensor/mock          边缘网关模拟器（独立 POM）
│   └── sensor/inference     FastAPI 推理服务
├── ruoyi-ui                 前端（Vue 2）
├── docs                     项目总览、实时诊断部署方案与运维说明
├── sql                      历史 SQL（空库安装/溯源，非生产迁移）
├── .local-models            模型制品（不提交）
├── .local-data              附件、上传、日志、PID（不提交）
├── pom.xml                  根 Maven reactor
├── start-all.ps1            一键启动脚本
└── SECURITY_*.md            安全审计与修复追踪
```

## 常用验证命令

```powershell
# Java
mvn clean test package

# 前端
Set-Location ruoyi-ui
npm run build:prod
npm run check:bundle
npm run test:e2e

# Python
.\.venv\Scripts\python.exe -m pytest ruoyi-sensor\inference\tests
```

## 生产部署要点

- 生产经 Nginx（HTTPS）对外，Java/Python 仅绑定内部地址；
- 生产推理拆分为 `phm-infer-gear:5001` 与 `phm-infer-bearing:5002`；Java 不因单个推理 worker 不可用而停止采集和存储；
- Redis 实时任务流启用 AOF（`everysec`），任务以 10 秒截止时间和最多两次尝试保障新鲜度，过期任务不补发陈旧告警；
- 8888 仅允许可信采集内网，5001/5002、Java 管理端口、Redis、MySQL、IoTDB 不对用户开放；
- MySQL、Redis、IoTDB 与采集端口受防火墙限制；
- 使用 `.env` 注入真实秘密（`MYSQL_PASSWORD`、`SENSOR_INFERENCE_INTERNAL_TOKEN` 等），严禁沿用开发默认值；
- 首次启动通过 `INITIAL_ADMIN_PASSWORD` 初始化管理员并强制改密；
- 低代码生产必须配置独立 `LOWCODE_DB_*` 账号与出站代理，写默认关闭；
- 当前项目仅提供单机本地启动方式，使用根目录 `start-all.ps1`；
- MAT 接入部署、迁移、接口和验收矩阵见 `AI_PROJECT_OVERVIEW.md`；
- 发布前按 `SECURITY_REMEDIATION_TRACKER.md` 的“发布前外部关闭项”逐项完成。

## 适用场景

- 工业设备健康管理与状态监测
- 振动/温度实时监测与告警
- 齿轮、轴承智能诊断与历史追溯
- 设备状态可视化大屏、测点总览
- 校园/实验室/科研设备监控（毕业设计、课程设计、团队协作）
