# 八通道振动监测闭环落地计划

## 目标

把八通道振动监测从“页面展示组件”落到可运行闭环：

采集/模拟上传 -> MySQL 入库 -> PHM 规则判定 -> WebSocket 实时推送 -> 八通道页面刷新 -> 最近数据冷启动 -> 冒烟脚本验收。

当前版本面向毕业设计和演示验收，优先保证链路完整、页面可展示、验证可重复。

## 已落地链路

1. 数据上传
   - 接口：`POST /system/vibration/upload`
   - 数据字段：`deviceCode`、`channelId`、`temperatureValue`、`vibrationValue`、`accelerationValue`、`sampleTime`
   - 时间格式：兼容 `yyyy-MM-dd HH:mm:ss`

2. 数据入库
   - 表：`device_vibration_data`
   - 八通道核心索引：`device_code + channel_id + sample_time`
   - `temperature_value` 与 `acceleration_value` 已纳入表结构、mapper、初始化 SQL

3. 实时推送
   - WebSocket 端点：`/ws/sensor`
   - 订阅消息：`{"type":"subscribe","channel":"overview"}`
   - 增量消息包含：`channelId`、`vibrationValue`、`temperatureValue`、`rms`、`sampleTime`

4. 页面冷启动
   - 页面加载时调用：`GET /system/vibration/recent`
   - 把最近数据按 `channelId` 归一化到 1-8 通道缓存
   - 没有 WebSocket 新消息时，页面也能先展示最近数据

5. 页面实时刷新
   - 页面订阅 `overview` 和 `phm_alarm`
   - 兼容 top-level、`data`、`payload`、JSON 字符串 `message` 等消息结构
   - 兼容 `channelId/channel/channelNo/ch` 和 `vibrationValue/rms/value` 等字段名

6. 验证脚本
   - 脚本：`setup/eight-channel-smoke-test.ps1`
   - 验证内容：WebSocket 握手、1-8 通道上传、recent 回读、WebSocket 通道级增量推送

## 运行步骤

1. 编译安装后端模块

```powershell
mvn -DskipTests install
```

2. 启动后端

```powershell
mvn -pl ruoyi-admin spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments="--sensor.startup.inference.enabled=false --sensor.startup.receiver.enabled=false --sensor.tcp.enabled=false --sensor.channel-tcp.enabled=false"
```

3. 验证八通道闭环

```powershell
.\setup\eight-channel-smoke-test.ps1 -BaseUrl http://localhost:8080
```

4. 启动前端并访问八通道页面

```powershell
cd ruoyi-ui
npm run dev
```

访问前端路由：`/system/multi-channel`。

## 演示讲解顺序

1. 先展示八通道页面，说明页面支持 1-8 通道卡片、趋势图、健康度、聚焦模式。
2. 运行 `setup/eight-channel-smoke-test.ps1`，模拟 8 个通道各上传 1 条振动样本。
3. 刷新页面，说明页面通过 `/system/vibration/recent` 自动加载最近数据。
4. 再次运行脚本，观察 WebSocket 推送带来的实时刷新。
5. 切换通道筛选和通道聚焦，展示单通道趋势和指标。
6. 说明同一上传事件会进入 PHM 规则判定，后续可在告警中心形成处理闭环。

## 后续完善计划

### 第一阶段：八通道稳定运行

- 将设备编码选择加入页面工具栏，默认展示 `DEV-001`。
- 给页面增加连接状态：未连接、连接中、已连接、重连中。
- 给 recent 接口增加 `deviceCode`、`limit` 参数，避免多设备数据混在同一页面。
- 给 smoke 脚本增加 `-DeviceCode` 和可选清理策略。

### 第二阶段：告警闭环联动

- 按 `deviceCode + channelId + featureType` 匹配 `phm_alarm_rule`。
- 上传后生成或更新 `phm_alarm_event`。
- WebSocket 推送 `phm_alarm`，页面通道卡片显示告警态。
- 点击告警态通道，跳转告警详情或打开处理弹窗。

### 第三阶段：诊断联动

- 将八通道 RMS/Peak/温度与 Python 推理结果合并展示。
- 通道 1 保留深度推理结果，通道 2-8 展示规则诊断结果。
- 诊断结果关联设备、测点、告警事件，形成“数据 -> 诊断 -> 告警 -> 处置”链路。

### 第四阶段：报表归档

- 报表中心增加八通道运行摘要。
- 支持按设备、通道、时间范围导出 Excel。
- 服务报告支持 PDF 上传、查看、下载，与设备大事记关联。

## 验收标准

- 后端 `mvn -DskipTests compile` 通过。
- 前端 `npm run build:prod` 通过。
- `setup/eight-channel-smoke-test.ps1` 通过。
- 八通道页面首次打开能展示 recent 数据。
- 上传新样本后，页面无需刷新即可收到 WebSocket 增量更新。
- 任一通道缺失数据时，不影响其他通道渲染。
- WebSocket 断开后可自动重连。
