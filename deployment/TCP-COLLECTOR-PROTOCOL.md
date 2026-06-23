# 认证 TCP 采集协议 v1

生产端口由 `sensor.channel-tcp` 控制，只能绑定专用工业网卡，并由防火墙限制网关来源。
旧的 `sensor.tcp` CSV 接收器在生产环境必须保持关闭。

每次连接只提交一个波形帧：

1. 第一行发送 `AUTH ` 加 JSON；
2. 后续发送 CSV 波形内容；
3. 以空行结束；
4. 平台将完整帧写入 Redis Stream 后返回接收结果。

认证示例：

```text
AUTH {"collectorId":"GW-01","timestamp":1782220000,"nonce":"随机值","deviceCode":"DEV-001","frameId":"全局唯一ID","sequence":1001,"sampleRate":25600,"sampleTime":1782220000123,"quality":"GOOD","signature":"十六进制HMAC"}
```

HMAC-SHA256 的 canonical 文本为：

```text
TCP
GW-01
1782220000
随机值
DEV-001
```

使用创建采集凭据时一次性返回的明文 secret 作为 HMAC 密钥。时间偏差默认不得超过
300 秒；`nonce` 在有效窗口内只能使用一次；凭据的设备范围必须包含 `deviceCode`。

成功响应包含 `eventId`（即 frameId）、`acceptedAt`、`duplicate` 和 `queueStatus`。
只有 `queueStatus=PERSISTED` 或 `DUPLICATE` 才允许网关删除本地缓冲帧。

参考实现位于 `ruoyi-sensor/mock`，具备至少 24 小时保护语义的磁盘缓冲与顺序补传。
