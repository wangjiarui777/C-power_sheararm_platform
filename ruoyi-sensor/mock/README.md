# 可靠边缘采集网关参考实现

该独立 JAR 演示生产采集端的关键语义：

- 每帧先原子写入本地磁盘，再尝试发送；
- 使用每网关独立凭据、时间戳、nonce 和 HMAC-SHA256 完成 TCP 认证；
- 保持原 `frameId` 与 `sequence` 重传，平台据此幂等；
- 仅在平台返回 `PERSISTED` 或 `DUPLICATE` 后删除本地帧；
- 断网恢复后按 sequence 顺序补传；
- 磁盘达到预算时，24 小时内的数据仍不会被删除。

## 构建

```powershell
cd ruoyi-sensor\mock
mvn clean package
```

## 运行

先在平台创建采集凭据，并配置环境变量：

```powershell
$env:PHM_COLLECTOR_ID='GW-01'
$env:PHM_COLLECTOR_SECRET='<创建凭据时一次性显示的明文>'
$env:PHM_DEVICE_CODE='DEV-001'
$env:PHM_HOST='10.10.0.20'
$env:PHM_PORT='8891'
$env:PHM_BUFFER_DIR='D:\phm-gateway-buffer'
java -jar target\vibration-simulator-1.0.0.jar
```

可选配置：

- `PHM_MAX_BUFFER_BYTES`：磁盘缓冲预算，默认 10 GiB；
- `PHM_SAMPLE_INTERVAL_MS`：示例采样帧间隔，默认 1000 ms；
- `PHM_SAMPLE_RATE`：采样率，默认 25600 Hz；
- `PHM_CONNECT_TIMEOUT_MS` / `PHM_READ_TIMEOUT_MS`：连接与响应超时。

平台端仅应在工业网卡上开启 `sensor.channel-tcp`，并通过 Windows 防火墙限制网关来源。
