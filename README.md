# RuoYi-Vue-master（传感器数据监测扩展版）

## 项目简介

本项目基于 **若依（RuoYi-Vue）前后端分离框架** 构建，在原有通用后台管理能力之上，新增了面向传感器数据采集、计算、展示与实时推送的业务扩展模块。项目整体采用 **Spring Boot + MyBatis/MyBatis-Plus + WebSocket + Vue** 的技术体系，适用于需要进行设备/传感器数据接入、实时监测、后台管理与业务扩展的场景。

项目通过模块化设计将基础能力、业务系统、定时任务、代码生成、传感器处理等能力拆分到不同 Maven 子模块中，便于维护、扩展与二次开发。

## 核心功能

### 1. 基础后台管理
- 用户、角色、菜单、权限等统一管理
- 系统参数配置与字典管理
- 登录认证、会话控制、权限校验
- 通用日志、任务调度、代码生成等后台能力

### 2. 传感器业务扩展
- 传感器相关业务模型与服务封装
- 数据接入与实时处理能力
- 面向实时监测场景的 WebSocket 推送
- 支持将采集后的特征值/实时数据广播给前端页面

### 3. 实时数据展示
- 前端可通过 WebSocket 订阅 `/ws/sensor`
- 后端主动推送最新数据到所有在线客户端
- 适用于曲线图、仪表盘、实时状态面板等场景

### 4. 数据计算与分析
- 结合 `Netty`、`JTransforms`、`commons-math3` 等组件完成数据处理与计算
- 可用于信号分析、频域处理、特征提取等扩展逻辑
- 支持与数据库、消息推送、接口层解耦协作

## 技术栈

### 后端
- **Java 17**
- **Spring Boot 3.4.5**
- **MyBatis / MyBatis-Plus**
- **Spring WebSocket**
- **Spring AOP**
- **Fastjson2**
- **Druid**
- **PageHelper**
- **Swagger / SpringDoc OpenAPI**
- **Lombok**
- **Netty**
- **TDengine JDBC**

### 前端
- **Vue**
- 若依前端框架及其生态组件

### 构建与工程化
- **Maven 多模块工程**
- 模块化分层设计
- 统一依赖版本管理

## 系统架构

本项目采用典型的前后端分离、多模块分层架构：

```text
前端页面（Vue）
   │
   ├── REST API 调用 ───────────────► 后端业务接口层
   │
   ├── WebSocket 连接（/ws/sensor）► 实时推送通道
   │
   ▼
后端 Spring Boot 应用
   ├── ruoyi-admin        启动入口与 Web 接口聚合
   ├── ruoyi-framework    安全、权限、配置、拦截器等基础框架
   ├── ruoyi-system       用户、角色、菜单、系统业务
   ├── ruoyi-common       公共工具类、通用返回、常量、异常处理
   ├── ruoyi-quartz       定时任务调度
   ├── ruoyi-generator    代码生成
   └── ruoyi-sensor       传感器业务、实时计算、WebSocket 推送
```

### 架构特点
- **前后端分离**：前端与后端通过接口通信，降低耦合度
- **模块化设计**：按职责拆分子模块，便于独立开发与维护
- **实时通信**：通过 WebSocket 实现低延迟推送，满足监控场景
- **可扩展性强**：可继续接入消息队列、缓存、采集网关等能力

## 功能实现原理

### 1. 后端启动机制
项目主启动类位于 `ruoyi-admin` 模块中：
- 使用 `@SpringBootApplication` 启动 Spring 容器
- 通过 `@ComponentScan(basePackages = { "com.ruoyi" })` 扫描所有子模块组件
- 使用 `@EnableAsync` 启用异步任务能力
- 排除 `DataSourceAutoConfiguration`，避免启动时自动装配冲突，便于项目对数据源进行自定义配置

### 2. 传感器实时推送机制
`ruoyi-sensor` 模块中通过 `@ServerEndpoint("/ws/sensor")` 定义 WebSocket 端点：
- 客户端建立连接后，服务端将 `Session` 保存到并发集合中
- 当传感器产生新数据时，业务层调用广播方法
- 服务端将对象序列化为 JSON，并异步推送到所有在线会话
- 客户端即可实时获取最新监测结果

该机制比轮询更高效，适合高频或准实时数据展示。

### 3. 数据处理机制
传感器模块引入了以下依赖用于数据分析与通信：
- `Netty`：用于高性能网络通信与底层数据处理扩展
- `JTransforms`：可用于 FFT 等数学变换与信号分析
- `commons-math3`：提供统计计算、数值算法支持
- `TDengine JDBC`：支持时序数据存储与查询

这使得项目不仅能“展示数据”，也能对采集数据进行进一步计算和处理。

## 主要模块说明

### `ruoyi-admin`
- 项目启动入口
- Web 接口聚合层
- Spring Boot 配置中心

### `ruoyi-framework`
- 安全认证与权限体系
- 拦截器、过滤器、异常处理
- 统一配置与通用基础设施

### `ruoyi-system`
- 用户、角色、部门、菜单、岗位等业务
- 系统管理相关核心功能

### `ruoyi-common`
- 通用工具、常量、统一响应体、异常类
- 公共实体与基础封装

### `ruoyi-quartz`
- 定时任务调度能力
- 适合周期性采集、清洗、同步、统计

### `ruoyi-generator`
- 代码生成器
- 快速生成 CRUD 模板与基础代码

### `ruoyi-sensor`
- 传感器业务核心模块
- 数据处理、实时广播、分析扩展
- WebSocket 实时通道 `/ws/sensor`

## WebSocket 设计说明

当前 WebSocket Handler 的实现逻辑如下：

- `onOpen`：客户端连接时加入在线会话集合
- `onMessage`：预留订阅条件扩展
- `onClose`：客户端断开时移除会话
- `broadcast(...)`：将实时数据广播给所有在线客户端

这种设计具有以下优点：
- 实现简单，易于理解与扩展
- 使用并发集合保证多连接场景下的线程安全
- 支持异步发送，减少阻塞

## 运行环境要求

- JDK 17
- Maven 3.8+
- Node.js（前端开发环境，如需本地运行前端）
- MySQL / 业务所需数据库
- TDengine（如项目中启用了时序数据存储）
- Python 3.10+（用于 `inference_service.py` 副手推理服务）

## Python 副手推理服务

项目根目录新增了 `inference_service.py`，用于直接加载 `./get/best_model_classwise_maha.pth` 并分析 `./get/got/*.mat` 文件。

启动方式如下：

```powershell
pip install -r requirements.txt
python inference_service.py
```

启动后默认监听 `0.0.0.0:5000`，可通过以下接口访问：

- `GET /health`
- `GET /analyze?file_name=data001`

返回结果包含：`label`、`confidence`、`time_axis`、`time_data`、`freq_axis`、`freq_data`。

## 本地启动方式

### 方式一：使用 PowerShell 启动脚本
根目录下提供了 `run-admin.ps1`，其执行逻辑为：
1. 先构建全部模块
2. 再进入 `ruoyi-admin` 启动后端服务

可直接运行：

```powershell
.\run-admin.ps1
```

### 方式二：Maven 手动启动
先在项目根目录构建：

```bash
mvn clean install -DskipTests
```

再启动主应用：

```bash
cd ruoyi-admin
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## 项目亮点

- 基于若依成熟架构，开发效率高
- 采用 Maven 多模块拆分，职责清晰
- 集成 WebSocket，支持传感器实时推送
- 适合工业监测、实验室数据采集、设备状态看板等场景
- 后续可平滑扩展消息队列、缓存、图表大屏、告警中心等能力

## 适用场景

- 传感器实时监测平台
- 设备状态可视化看板
- 工业数据采集与分析系统
- 时序数据展示与告警系统
- 校园/实验室/科研设备监控项目

## 目录结构概览

```text
RuoYi-Vue-master
├── ruoyi-admin
├── ruoyi-common
├── ruoyi-framework
├── ruoyi-generator
├── ruoyi-quartz
├── ruoyi-sensor
├── ruoyi-system
├── run-admin.ps1
└── pom.xml
```

## 说明

本 README 侧重从 **功能实现、技术原理与系统架构** 三个层面介绍项目，适合作为课程设计、毕业设计、项目答辩或团队协作文档的基础说明。如果你希望，我还可以继续为你补充：

- 数据库设计说明
- 接口文档说明
- 部署文档
- 项目答辩版简介
- 更适合 GitHub 展示的精简版 README
