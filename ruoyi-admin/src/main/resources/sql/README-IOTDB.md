# Apache IoTDB 初始化说明

## 1. 目标版本与集群建议

- 服务端：Apache IoTDB 2.0.x
- Java 客户端：`org.apache.iotdb:iotdb-session`，版本必须与服务端一致
- 推荐集群：`3 ConfigNode + 3 DataNode`
- 推荐参数：
  - `schema_replication_factor=3`
  - `data_replication_factor=2`
  - `timestamp_precision=us`

## 2. 初始化顺序

先确认 IoTDB 集群可连通，然后执行同目录下的 `iotdb-init.sql`。脚本中的每条
SQL 都保持为单行，可直接输入 IoTDB 2.0.x Table CLI；脚本可重复执行。

初始化脚本会创建遥测指标、振动帧和模型诊断结果三张表。诊断结果默认保留
3650 天，可通过 `IOTDB_DIAGNOSIS_TTL_DAYS` 调整；模型输出写入失败时由
MySQL 表 `diagnosis_iotdb_sync` 持久化记录并自动补偿，不会将已完成推理改为失败。

Windows PowerShell 示例：

```powershell
$sql = (Get-Content -Raw .\iotdb-init.sql) + "`nexit;`n"
$sql | & "$env:IOTDB_HOME\sbin\windows\start-cli-table.bat" `
  -h 127.0.0.1 -p 6667 -u root -pw root -sql_dialect table
```

Linux 示例：

```bash
{ cat iotdb-init.sql; echo 'exit;'; } |
  "$IOTDB_HOME/sbin/start-cli.sh" \
  -h 127.0.0.1 -p 6667 -u root -pw root -sql_dialect table
```

脚本会创建：

- `telemetry_metric`：低频指标、质量、序列号
- `vibration_frame`：整帧波形、频谱、故障元数据

## 3. 配置键

后端统一使用：

```yaml
sensor:
  store-type: iotdb
  iotdb:
    enabled: true
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
    trust-store: ""
    trust-store-password: ""
    fetch-size: 512
    session-pool-size: 8
    ttl-days: 3650
    timestamp-precision: us
```

## 4. 常见检查项

- 连接失败：检查 `node-urls` 是否指向 DataNode RPC 端口 `6667`
- 建表失败：确认当前会话已 `USE monitoring`
- 版本不兼容：确认 `iotdb-session` 与 IoTDB 服务端完全同版本
- 空库启动无数据：这是本次迁移的预期行为，页面应显示空态而不是报错
- 服务未启动：主应用不会等待 IoTDB 连接；后台按 `reconnect-interval-seconds` 重试
- 空集群初始化：后端先用未绑定数据库的会话创建 `monitoring`，再创建绑定该数据库的正式连接池
- 健康状态：`/sensor/monitoring/timeseries/health` 返回
  `CONNECTING`、`AVAILABLE`、`UNAVAILABLE` 或 `DISABLED`，并包含最近连接、
  成功操作、成功写入、失败时间和错误摘要
