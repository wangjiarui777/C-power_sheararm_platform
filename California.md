# California

## 项目全局分析报告

**项目名称**：RuoYi-Vue 物联网振动监测与 PHM 扩展系统  
**生成目的**：用于迁移到新的 AI Agent 环境，梳理项目架构、数据链路、核心模块、存储设计与改进方向  
**技术定位**：以 RuoYi-Vue 为基础，叠加工业物联网（IIoT）采集、振动分析、告警与实时可视化能力的设备健康监测系统

---

## 1. 项目全景图（Project Overview）

### 1.1 核心业务定位

从代码实现来看，本项目并非单纯的通用后台管理系统，而是一个面向**旋转机械/风机类设备**的工业监测平台，核心业务聚焦于：

- 振动/温度数据采集
- 多通道原始数据解析
- 振动特征计算（RMS、峰值、峭度、波峰因数、频谱等）
- 故障诊断与告警判定
- 数据落库与时序存储
- WebSocket 实时推送
- 前端多通道健康监测看板

前端页面 `ruoyi-ui/src/views/system/vibration/index.vue` 显示出非常明确的 PHM 场景特征：

- 8 通道状态卡片
- RMS 与温度实时展示
- 健康度仪表盘
- 时域 / FFT 切换
- 最近告警事件列表
- 历史记录回放抽屉

### 1.2 基础框架识别

项目基于标准的 RuoYi 多模块工程，后端技术栈如下：

- **Java 17**
- **Spring Boot 3.4.5**
- **MyBatis-Plus 3.5.9**
- **MyBatis Spring Boot Starter 3.0.4**
- **Druid 1.2.28**
- **Netty 4.1.118.Final**
- **SpringDoc OpenAPI 2.8.9**
- **TDengine JDBC 3.6.0**
- **Jackson 2.18.3**
- **Lombok 1.18.36**

前端从写法上判断为 **Vue 2 + Element UI** 风格，而不是 Vue 3：

- 使用 `beforeDestroy`
- 使用 `slot-scope`
- 使用 `this.$set`
- 使用 `:visible.sync`
- 使用 `@click.native`

### 1.3 顶层模块概览

顶层 Maven 模块如下：

- `ruoyi-common`
- `ruoyi-system`
- `ruoyi-framework`
- `ruoyi-quartz`
- `ruoyi-generator`
- `ruoyi-sensor`
- `ruoyi-admin`

其中：

- `ruoyi-admin` 为启动入口
- `ruoyi-sensor` 为本项目工业监测能力的核心扩展模块
- `ruoyi-system` 仍承载一部分设备振动业务与通用系统实体

---

## 2. 核心模块拆解（Module Breakdown）

### 2.1 `ruoyi-admin`

**职责**：后端启动入口与模块整合。

关键类：

- `ruoyi-admin/src/main/java/com/ruoyi/RuoYiApplication.java`

该类：

- 排除了 `DataSourceAutoConfiguration`
- 扫描 `com.ruoyi` 全包
- 开启异步任务支持

这说明数据源与业务装配可能存在自定义化配置，尤其适用于 MySQL + TDengine 的混合数据场景。

### 2.2 `ruoyi-common`

**职责**：公共返回体、工具类、通用常量、基础实体和注解。

从控制器返回 `AjaxResult` 可确认该模块承载统一响应结构。

### 2.3 `ruoyi-framework`

**职责**：框架级能力，包括安全认证、拦截器、Web 配置、权限管理等。

### 2.4 `ruoyi-system`

**职责**：系统级通用业务实体与服务。

当前可见与振动业务相关的核心实体包括：

- `DeviceVibrationData`

说明系统级业务仍保留了设备原始振动采集数据模型。

### 2.5 `ruoyi-quartz`

**职责**：定时任务调度。

### 2.6 `ruoyi-generator`

**职责**：代码生成器。

### 2.7 `ruoyi-sensor`

**职责**：本项目的业务核心扩展模块。

其主要能力包括：

- TCP/Netty 数据接入
- 二进制协议解析
- 多通道振动分析
- FFT 与特征提取
- 告警判断
- 数据持久化
- TDengine 时序写入
- WebSocket 推送

---

## 3. `ruoyi-sensor` 核心类与职责

### 3.1 数据接入：`SensorTcpChannelHandler`

路径：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/SensorTcpChannelHandler.java`

职责：

- 接收 Netty `ByteBuf`
- 转换为 `byte[] payload`
- 封装成 `ChannelFrameDTO`
- 交给采集汇聚服务处理

关键特征：

- `deviceCode` 默认取 Netty channel id
- `batchId` 使用当前时间戳
- `sampleRate` 默认 `1000D`
- `collectTime` 使用当前时间

### 3.2 帧解析：`NettyChannelFrameParser`

路径：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/NettyChannelFrameParser.java`

职责：

- 将原始字节流按固定协议解析为通道数据
- 每通道读取 4 字节：
  - 2 字节振动原始值
  - 2 字节温度原始值
- 默认支持 8 通道映射
- 将原始值转换为物理量：
  - 振动 = `raw / 1000.0`
  - 温度 = `raw / 100.0`

### 3.3 采集汇聚：`ChannelFrameIngestServiceImpl`

路径：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/ChannelFrameIngestServiceImpl.java`

职责：

- 接收 `ChannelFrameDTO`
- 解析为多通道测点
- 转换成系统业务对象 `DeviceVibrationData`
- 构建分析用 `ChannelSignal`
- 调用振动分析服务完成特征提取与诊断
- 调用实时推送服务将结果发往前端

这是从原始数据到业务结果的核心枢纽。

### 3.4 特征提取与诊断：`VibrationAnalysisServiceImpl`

路径：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/VibrationAnalysisServiceImpl.java`

职责：

- 对振动信号做预处理
- 计算关键 PHM 特征：
  - RMS
  - Peak
  - Crest Factor
  - Kurtosis
  - Centroid Frequency
  - RMS Frequency
- 进行 FFT 频谱分析
- 调用规则方法 `judgeDiagnosis(...)` 输出诊断文本
- 将分析结果异步落库

这部分是当前系统最接近“PHM 算法核心”的位置。

### 3.5 多通道并行分析：`VibrationChannelBatchAnalyzer`

路径：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/VibrationChannelBatchAnalyzer.java`

职责：

- 使用线程池并行处理多个通道
- 对每个通道调用 `VibrationAnalysisServiceImpl.analyze(...)`
- 汇总为 `MultiChannelAnalysisVo`

注意：线程池是方法内临时创建，适合低中频任务，但不适合高频持续流式负载。

### 3.6 告警存储：`SensorAlarmServiceImpl`

路径：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/SensorAlarmServiceImpl.java`

职责：

- 作为 MyBatis-Plus 服务层承载 `sensor_alarm` 表的增删改查

### 3.7 WebSocket 推送：`SensorWebSocketHandler`

路径：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/websocket/SensorWebSocketHandler.java`

职责：

- 维护当前连接的 WebSocket Session 集合
- 提供广播方法 `broadcast(...)`
- 将实时健康信息发送到前端

Endpoint：

- `/ws/sensor`

### 3.8 TDengine 写入：`SensorTdengineWriter`

路径：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/tdengine/SensorTdengineWriter.java`

职责：

- 预留 TDengine 原始波形与 FFT 点写入能力
- 当前实现仍以“模拟打印日志”为主
- 通过配置 `sensor.tdengine.enabled` 决定是否开启真实写入

---

## 4. 技术链路分析（Technical Pipeline）

### 4.1 从 Netty 到前端的完整链路

完整数据流可概括为：

1. **Netty 接收原始二进制数据**  
   类：`SensorTcpChannelHandler`

2. **封装采集帧 DTO**  
   类：`ChannelFrameDTO`

3. **按协议解析多通道数据**  
   类：`NettyChannelFrameParser`

4. **转换为业务实体并批量落库**  
   类：`ChannelFrameIngestServiceImpl`

5. **多通道振动特征提取**  
   类：`VibrationChannelBatchAnalyzer` + `VibrationAnalysisServiceImpl`

6. **告警与分析结果持久化**  
   类：`SensorStorageService`、`SensorAlarmServiceImpl`

7. **TDengine 写入原始波形与频域数据**  
   类：`SensorTdengineWriter`

8. **WebSocket 实时推送前端**  
   类：`SensorWebSocketHandler`

9. **前端看板展示与历史回放**  
   页面：`ruoyi-ui/src/views/system/vibration/index.vue`

### 4.2 告警判断与故障诊断位置

当前代码中的诊断逻辑主要位于：

- `ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/VibrationAnalysisServiceImpl.java`

其诊断依据主要是：

- 峭度
- 波峰因数
- 频谱结构
- 主频异常特征

这属于**经验规则型诊断**，是 PHM 系统早期阶段常见实现。

---

## 5. 数据库与持久化（Data Persistence）

### 5.1 MySQL 核心表

#### 5.1.1 `device_vibration_data`

对应实体：`DeviceVibrationData`

关键字段：

- `dataId`
- `deviceCode`
- `channelId`
- `temperatureValue`
- `vibrationValue`
- `sampleTime`
- `createTime`
- `updateTime`

用途：

- 保存设备原始振动监测记录
- 支撑历史查询与趋势分析

#### 5.1.2 `sensor_alarm`

对应实体：`SensorAlarmEntity`

关键字段：

- `id`
- `deviceCode`
- `alarmType`
- `alarmMessage`
- `sampleTime`
- `createTime`

用途：

- 保存告警事件
- 用于前端告警列表展示
- 用于运维追溯

#### 5.1.3 `vibration_analysis_batch`

对应实体：`VibrationAnalysisBatchEntity`

关键字段：

- `batchId`
- `deviceCode`
- `sampleRate`
- `sampleCount`
- `collectTime`
- `createTime`

用途：

- 保存批次级分析任务元数据

#### 5.1.4 `vibration_analysis_record`

对应实体：`VibrationAnalysisRecordEntity`

关键字段：

- `id`
- `batchId`
- `deviceCode`
- `rms`
- `peak`
- `crestFactor`
- `kurtosis`
- `centroidFrequency`
- `rmsFrequency`
- `diagnosisResult`
- `waveJson`
- `spectrumJson`
- `createTime`

用途：

- 保存一次分析的特征值与诊断结果
- 是 PHM 分析结果的核心持久化表

### 5.2 TDengine 时序设计

当前代码中已经预留了时序实体：

#### 5.2.1 原始波形点

实体：`SensorRawWaveEntity`

字段：

- `deviceCode`
- `ts`
- `value`
- `pointIndex`
- `sampleRate`

#### 5.2.2 FFT 频点

实体：`SensorFftPointEntity`

字段：

- `deviceCode`
- `ts`
- `frequency`
- `amplitude`
- `pointIndex`

设计意图：

- 以设备与时间戳为主轴存储高频时序数据
- 原始波形与频域数据分表管理
- 适合 TDengine 的超级表 + 子表范式

但需要注意：当前 `SensorTdengineWriter` 仍偏模拟实现，真实写入未完全闭环。

---

## 6. 核心入口与配置文件（Entry Points）

### 6.1 后端启动入口

- `ruoyi-admin/src/main/java/com/ruoyi/RuoYiApplication.java`

作用：

- Spring Boot 启动入口
- 扫描所有业务模块
- 开启异步执行

### 6.2 WebSocket 入口

- `ruoyi-sensor/src/main/java/com/ruoyi/sensor/websocket/SensorWebSocketHandler.java`
- 路径：`/ws/sensor`

### 6.3 WebSocket 配置

- `ruoyi-sensor/src/main/java/com/ruoyi/sensor/config/WebSocketConfig.java`
- 注册 `ServerEndpointExporter`

### 6.4 前端核心视图

- `ruoyi-ui/src/views/system/vibration/index.vue`

功能包括：

- 设备通道卡片
- 实时趋势图
- FFT 切换
- 健康度仪表盘
- 最近告警
- 详情抽屉与历史表格

### 6.5 前端 API 映射

- `ruoyi-ui/src/api/system/vibration.js`

当前接口包含：

- `GET /system/vibration/list`
- `GET /system/vibration/recent`
- `POST /system/vibration/upload`
- `GET /system/vibration/{dataId}`
- `POST /system/vibration`
- `PUT /system/vibration`
- `DELETE /system/vibration/{dataId}`

需要注意：该前端接口域与 `ruoyi-sensor` 中已见控制器路径并不完全一致，迁移时需统一。

---

## 7. TODO 与改进建议（TODO / Improvements）

### 7.1 符合 PHM 基础标准的部分

- 已具备采集、分析、告警、展示闭环
- 已具备典型振动特征计算
- 已具备多通道监测能力
- 已具备实时 WebSocket 推送

### 7.2 需要增强的部分

#### 7.2.1 告警算法智能化不足

当前以经验阈值与规则判断为主，建议增强为：

- 自适应阈值
- 设备工况分层策略
- 异常检测模型
- 故障分类模型
- 剩余寿命预测模型

#### 7.2.2 TDengine 写入未闭环

建议补齐：

- 真正的 TDengine 连接与 DAO
- 批量写入
- 超级表建模
- 写入失败重试
- 性能压测与容量评估

#### 7.2.3 Netty 接入层工程细节待补充

建议补充：

- 端口配置
- 协议头与粘包拆包
- 心跳机制
- 设备鉴权
- 异常重连与会话管理

#### 7.2.4 线程池管理不够工程化

`VibrationChannelBatchAnalyzer` 目前使用方法内线程池，建议改为：

- Spring 托管线程池
- 固定大小可配置
- 队列与拒绝策略
- 线程池监控指标

#### 7.2.5 前端实时链路存在模拟逻辑

`index.vue` 中存在 mock 数据与实时数据混合逻辑，建议：

- 生产环境关闭 mock
- 统一实时和历史数据契约
- 清晰区分“历史查询”和“在线流数据”

#### 7.2.6 路由接口需统一

当前前端 `/system/vibration/*` 与 `ruoyi-sensor` 的 `/sensor/vibration/batch/*` 不完全一致，建议统一接口域，避免迁移后出现适配成本。

---

## 8. 结论

综合当前代码可判断：本项目已经形成了一个较完整的工业设备振动监测平台，具备 PHM 体系中的核心工程能力，但诊断层仍偏规则驱动，智能化程度尚有提升空间。

若迁移到新的 AI Agent 环境，建议重点保留并重构以下部分：

1. 数据接入与协议解析
2. 振动特征提取与分析链路
3. 告警与诊断规则引擎
4. MySQL + TDengine 双存储设计
5. WebSocket 实时推送与前端看板
6. 统一 API 契约与模块边界

---

## 9. 关键文件索引

- 顶层 Maven 配置：`pom.xml`
- 启动类：`ruoyi-admin/src/main/java/com/ruoyi/RuoYiApplication.java`
- Netty 接入：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/SensorTcpChannelHandler.java`
- 帧解析：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/NettyChannelFrameParser.java`
- 数据汇聚：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/ChannelFrameIngestServiceImpl.java`
- 特征分析：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/VibrationAnalysisServiceImpl.java`
- 多通道分析：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/service/impl/VibrationChannelBatchAnalyzer.java`
- 告警实体：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/domain/entity/SensorAlarmEntity.java`
- TDengine 写入：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/tdengine/SensorTdengineWriter.java`
- WebSocket：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/websocket/SensorWebSocketHandler.java`
- WebSocket 配置：`ruoyi-sensor/src/main/java/com/ruoyi/sensor/config/WebSocketConfig.java`
- 前端页面：`ruoyi-ui/src/views/system/vibration/index.vue`
- 前端 API：`ruoyi-ui/src/api/system/vibration.js`
