# Mock 振动数据采集模拟器

这是一个独立的 Netty 振动传感器数据模拟程序，用于向本地后端 Netty 服务发送心跳与三轴振动采样数据。

## 功能

- 使用 Netty Client 连接到 `127.0.0.1:8088`
- 每 5 秒发送一次心跳包
- 每 1 秒发送一次三轴振动采样数据
- 使用正弦函数叠加随机噪声，模拟真实传感器波形
- 支持断线重连，服务端重启后可自动恢复连接
- 使用 `LengthFieldPrepender + StringEncoder` 封装消息

## 消息格式

当前发送的数据按 CSV 字符串组织，对齐如下字段：

```text
'__header__','__version__','__globals__',DE_time,sr,rpm,load,fault_type,fault_size
```

实际发送示例：

```text
'[\'__header__\', \'__version__\', \'__globals__\', \'DE_time\', \'sr\', \'rpm\', \'load\', \'fault_type\', \'fault_size\']','1.0','[]',0.512,25600.000,1500.000,0.750,'HEARTBEAT',0.000
```

其中：

- `DE_time`：振动信号采样值，使用正弦波叠加噪声模拟
- `sr`：采样率，默认 `25600`
- `rpm`：转速，默认 `1500`
- `load`：负载，默认 `0.75`
- `fault_type`：当前样本类型，心跳为 `HEARTBEAT`，振动样本为 `VIBRATION`
- `fault_size`：用作故障强度/幅值的模拟值

如果后端协议字段有固定的 CSV 解析规则，只需要同步修改 `src/main/java/com/ruoyi/mock/VibrationSimulatorApplication.java` 中的 `buildMessage` 方法即可。

## 目录结构

```text
mock/
├── pom.xml
├── README.md
└── src/main/java/com/ruoyi/mock/VibrationSimulatorApplication.java
```

## 编译与运行

### 1. 进入目录

```powershell
cd mock
```

### 2. 打包

```powershell
mvn clean package
```

打包后会生成一个包含依赖的可执行 JAR。

### 3. 启动

```powershell
java -jar target/vibration-simulator-1.0.0.jar
```

## 自定义连接地址

默认连接：

- Host: `127.0.0.1`
- Port: `8088`

也可以在启动时传入参数：

```powershell
java -jar target/vibration-simulator-1.0.0.jar 127.0.0.1 8088
```

## 说明

- 如果你的后端 Netty 端口不是 `8088`，请修改启动参数或代码中的默认端口。
- 为了和后端接收逻辑保持一致，建议先确认 `ruoyi-netty` 或对应服务端模块实际解析的消息格式，再同步调整 `buildMessage`。
- 当前实现采用基础字符串协议，便于快速联调与验证。
