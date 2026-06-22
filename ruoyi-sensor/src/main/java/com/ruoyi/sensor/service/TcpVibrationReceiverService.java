package com.ruoyi.sensor.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import com.ruoyi.sensor.domain.dto.SensorSampleDto;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.domain.dto.VibrationFrameEnvelope;
import com.ruoyi.sensor.domain.dto.VibrationCsvRecord;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;

/**
 * TCP 振动数据接收服务。
 * <p>
 * 程序启动后会监听指定端口，接收外部设备通过 TCP 发送的振动数据，
 * 解析后批量保存到数据库，并推送实时数据到 WebSocket。
 */
@Service
public class TcpVibrationReceiverService implements ApplicationRunner
{
    private static final Logger log = LoggerFactory.getLogger(TcpVibrationReceiverService.class);

    // 支持的时间格式：精确到毫秒、精确到秒、ISO_LOCAL_DATE_TIME
    private static final DateTimeFormatter[] TIME_FORMATS = new DateTimeFormatter[] {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    // 实时推送服务，用于把解析后的数据发给前端
    private final SensorWebSocketPushService pushService;
    // 时序库写入器：负责把分析后的原始波形、FFT 和诊断结果写入时序库
    private final TimeSeriesStore timeSeriesStore;
    // 单线程执行器：用于后台启动 TCP 监听循环
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // 标记服务是否已经启动，避免重复开启监听线程
    private final AtomicBoolean running = new AtomicBoolean(false);

    // TCP 监听端口，默认 8890，8888 保留给 CwruMatReceiver。
    @Value("${sensor.tcp.port:8890}")
    private int tcpPort;

    // 是否启用 TCP 接收功能，默认开启
    @Value("${sensor.tcp.enabled:true}")
    private boolean enabled;

    // 采样频率，默认 4096Hz，满足 1k-10kHz 范围要求，可通过配置覆盖
    @Value("${sensor.vibration.sample-rate:4096}")
    private int sampleRate;


    public TcpVibrationReceiverService(SensorWebSocketPushService pushService, TimeSeriesStore timeSeriesStore)
    {
        this.pushService = pushService;
        this.timeSeriesStore = timeSeriesStore;
    }

    /**
     * Spring Boot 启动完成后自动执行。
     * 如果配置中关闭了 TCP 接收，则直接返回；否则启动后台监听线程。
     */
    @Override
    public void run(ApplicationArguments args)
    {
        if (!enabled)
        {
            log.info("TCP vibration receiver disabled by configuration.");
            return;
        }
        if (running.compareAndSet(false, true))
        {
            executor.submit(this::acceptLoop);
        }
    }

    /**
     * TCP 监听主循环：创建 ServerSocket 并持续接收客户端连接。
     */
    private void acceptLoop()
    {
        try (ServerSocket serverSocket = new ServerSocket(tcpPort))
        {
            log.info("TCP vibration receiver started on port {}", tcpPort);
            while (running.get())
            {
                Socket socket = serverSocket.accept();
                handleClient(socket);
            }
        }
        catch (IOException ex)
        {
            log.error("TCP vibration receiver failed", ex);
        }
    }

    /**
     * 处理单个 TCP 客户端。
     * <p>
     * 协议约定：
     * <ul>
     *   <li>每行是一条数据或控制命令</li>
     *   <li>空行表示一批数据结束，需要立即落库</li>
     *   <li>KEEPALIVE 表示心跳，直接忽略</li>
     * </ul>
     */
    private void handleClient(Socket socket)
    {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        log.info("TCP client connected: {}", remote);
        try (Socket client = socket;
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)))
        {
            // 临时缓存一批完整文本，遇到空行后统一解析
            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                String trimmed = line.trim();
                // 空行：说明一批数据结束，开始处理缓存内容
                if (trimmed.isEmpty())
                {
                    flushBatch(buffer);
                    continue;
                }
                // 心跳包：只保活，不参与业务处理
                if ("KEEPALIVE".equalsIgnoreCase(trimmed))
                {
                    continue;
                }
                buffer.append(trimmed).append('\n');
            }
            // 客户端断开前，最后再刷一次缓存
            flushBatch(buffer);
        }
        catch (Exception ex)
        {
            log.warn("TCP client disconnected or parse error: {}", remote, ex);
        }
    }

    /**
     * 将一批文本数据解析为实体对象，并批量入库。
     */
    private void flushBatch(StringBuilder buffer)
    {
        if (buffer.length() == 0)
        {
            return;
        }
        String batch = buffer.toString();
        buffer.setLength(0);

        // 解析每一行数据
        List<String[]> rows = parseRows(batch);
        if (rows.isEmpty())
        {
            return;
        }

        List<DeviceVibrationData> entities = new ArrayList<>();
        for (String[] row : rows)
        {
            DeviceVibrationData entity = toEntity(row);
            if (entity != null)
            {
                entities.add(entity);
                // 同时推送实时数据到前端
                pushRealtime(entity);
            }
        }

        // 解析后的数据才是后续分析的输入，先提取加速度序列，再进行 RMS 和 FFT 计算
        if (!entities.isEmpty())
        {
            processAndWriteTimeSeries(entities);
        }
    }

    /**
     * 将批次文本按行拆分，过滤表头、注释行和无效行。
     * <p>
     * 期望格式大致为：timestamp,channelId,voltage,acceleration
     */
    private List<String[]> parseRows(String batch)
    {
        List<String[]> rows = new ArrayList<>();
        String[] lines = batch.split("\\r?\\n");
        for (String rawLine : lines)
        {
            String line = rawLine.trim();
            // 过滤空行、表头、注释行
            if (line.isEmpty() || line.startsWith("timestamp") || line.startsWith("#"))
            {
                continue;
            }
            String[] cols = line.split(",");
            // 至少要有 4 列：时间、通道、振动电压、加速度
            if (cols.length < 4)
            {
                continue;
            }
            rows.add(cols);
        }
        return rows;
    }

    /**
     * 解析批次后执行振动分析，并写入统一时序存储。
     * <p>
     * 当前版本只保留单通道处理：整批数据只取第一条数据的通道信息和序列进行分析。
     * 这样可以避免多通道混批导致的子表错写问题，也便于先稳定验证链路。
     */
    private void processAndWriteTimeSeries(List<DeviceVibrationData> entities)
    {
        if (entities == null || entities.isEmpty())
        {
            return;
        }

        // 只处理第一条数据对应的通道，当前阶段先固定为单通道链路
        Integer channelId = entities.get(0).getChannelId() == null ? 1 : entities.get(0).getChannelId();
        String deviceCode = entities.get(0).getDeviceCode();
        long sampleTime = entities.get(0).getSampleTime() == null ? System.currentTimeMillis()
            : entities.get(0).getSampleTime().getTime();

        // 1. 提取当前批次的加速度序列作为振动分析输入
        double[] accelerationSeries = new double[entities.size()];
        for (int i = 0; i < entities.size(); i++)
        {
            BigDecimal acceleration = entities.get(i).getAccelerationValue();
            accelerationSeries[i] = acceleration == null ? 0D : acceleration.doubleValue();
        }

        // 2. 构造原始波形对象
        SensorSampleDto sample = new SensorSampleDto(deviceCode, sampleTime, sampleRate, accelerationSeries);

        // 3. 计算 RMS：表征这批振动的整体能量水平
        double rms = calculateRMS(accelerationSeries);

        // 4. FFT 计算：先补齐到 2 的幂次方，再做频谱变换
        double[] fftInput = padToPowerOfTwo(accelerationSeries);
        FastFourierTransformer transformer = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] fftResult = transformer.transform(fftInput, TransformType.FORWARD);
        List<Double> amplitudes = new ArrayList<>();
        // 只保留单边谱，避免重复频率分量
        for (int i = 0; i < fftResult.length / 2; i++)
        {
            amplitudes.add(fftResult[i].abs() / fftInput.length);
        }
        // 5. 组织诊断信息：这里只保留 RMS 等摘要指标，rpm 不再参与写入
        VibrationCsvRecord record = new VibrationCsvRecord(
            new com.ruoyi.common.domain.dto.VibrationCsvProtocol(
                "sensor_vibration_csv_st",
                "1.0",
                "rms=" + rms,
                rms,
                (double) sampleRate,
                0D,
                0D,
                "normal",
                0D));
        VibrationFrameEnvelope envelope = new VibrationFrameEnvelope();
        envelope.setDeviceCode(deviceCode);
        envelope.setChannelId(channelId);
        envelope.setSampleRate(sampleRate);
        envelope.setSampleCount(accelerationSeries.length);
        envelope.setWaveform(toList(sample.getWaveform()));
        envelope.setSpectrum(amplitudes);
        envelope.setFreqStep(amplitudes.isEmpty() ? null : sampleRate / (2D * amplitudes.size()));
        envelope.setRpm(record.getRpm());
        envelope.setLoad(record.getLoad());
        envelope.setFaultType(record.getFaultType());
        envelope.setFaultSize(record.getFaultSize());
        envelope.setQuality("GOOD");
        envelope.setAxis("radial");
        envelope.setUnit("mm/s");
        envelope.setSampleTime(new Date(sampleTime));
        envelope.setReceiveTime(new Date());
        timeSeriesStore.writeVibrationFrame(envelope);
        TelemetryEnvelope telemetry = new TelemetryEnvelope();
        telemetry.setDeviceCode(deviceCode);
        telemetry.setChannelId(channelId);
        telemetry.setMetricCode("vibration");
        telemetry.setSignalType("vibration");
        telemetry.setSource("tcp-vibration-receiver");
        telemetry.setUnit("mm/s");
        telemetry.setValue(rms);
        telemetry.setQuality("GOOD");
        telemetry.setSampleTime(new Date(sampleTime));
        telemetry.setReceiveTime(new Date());
        telemetry.setSequence(sampleTime);
        telemetry.normalize();
        timeSeriesStore.writeTelemetry(telemetry);
    }

    /**
     * 计算序列的均方根值（RMS）。
     */
    private double calculateRMS(double[] values)
    {
        if (values == null || values.length == 0)
        {
            return 0D;
        }
        double sumSquares = 0D;
        for (double value : values)
        {
            sumSquares += value * value;
        }
        return Math.sqrt(sumSquares / values.length);
    }

    /**
     * 将 FFT 输入补齐到 2 的幂次方，便于 FFT 高效计算。
     */
    private double[] padToPowerOfTwo(double[] values)
    {
        if (values == null || values.length == 0)
        {
            return new double[] {0D};
        }
        int size = 1;
        while (size < values.length)
        {
            size <<= 1;
        }
        double[] padded = new double[size];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }

    private List<Double> toList(double[] values)
    {
        List<Double> result = new ArrayList<>(values.length);
        for (double value : values)
        {
            result.add(value);
        }
        return result;
    }

    /**
     * 将一行 CSV 数据转换为数据库实体。
     * <p>
     * 失败则返回 null，表示该行数据无效。
     */
    private DeviceVibrationData toEntity(String[] cols)
    {
        try
        {
            String timestamp = cols[0].trim();
            Integer channelId = Integer.valueOf(cols[1].trim());
            BigDecimal voltage = new BigDecimal(cols[2].trim());
            BigDecimal acceleration = new BigDecimal(cols[3].trim());

            DeviceVibrationData data = new DeviceVibrationData();
            // 设备编码这里写死为 collector，表示数据采集器来源
            data.setDeviceCode("collector");
            data.setChannelId(channelId);
            // 这里把 voltage 同时写入温度值和振动值，说明当前协议可能复用了字段
            data.setTemperatureValue(voltage);
            data.setVibrationValue(voltage);
            data.setAccelerationValue(acceleration);
            data.setSampleTime(parseDate(timestamp));
            data.setCreateTime(new Date());
            return data;
        }
        catch (Exception ex)
        {
            log.warn("Skip invalid vibration row: {}", String.join(",", cols), ex);
            return null;
        }
    }

    /**
     * 将持久化对象转换为实时推送对象，并发送给前端。
     */
    private void pushRealtime(DeviceVibrationData data)
    {
        ChannelRealtimeVo vo = new ChannelRealtimeVo();
        vo.setDeviceCode(data.getDeviceCode());
        vo.setChannelId(data.getChannelId());
        vo.setSampleTime(toLocalDateTime(data.getSampleTime()));
        vo.setVibrationValue(data.getVibrationValue() == null ? null : data.getVibrationValue().doubleValue());
        vo.setTemperatureValue(data.getTemperatureValue() == null ? null : data.getTemperatureValue().doubleValue());
        vo.setAccelerationValue(data.getAccelerationValue() == null ? null : data.getAccelerationValue().doubleValue());
        // 这里把振动值直接当作 RMS
        vo.setRms(data.getVibrationValue() == null ? null : data.getVibrationValue().doubleValue());
        // 峰值按 RMS 的一个固定倍数估算
        vo.setPeak(data.getVibrationValue() == null ? null : data.getVibrationValue().doubleValue() * 1.25D);
        vo.setAlarm(Boolean.FALSE);
        pushService.pushFeature(vo);
    }

    /**
     * 尝试把字符串解析成时间。
     * 支持多种常见格式；若都失败，则尝试按时间戳解析；再失败则返回当前时间。
     */
    private Date parseDate(String text)
    {
        for (DateTimeFormatter formatter : TIME_FORMATS)
        {
            try
            {
                LocalDateTime ldt = LocalDateTime.parse(text, formatter);
                return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
            }
            catch (DateTimeParseException ignore)
            {
                // try next
            }
        }
        try
        {
            long epoch = Long.parseLong(text);
            return new Date(epoch);
        }
        catch (Exception ex)
        {
            return new Date();
        }
    }

    /**
     * 把 java.util.Date 转成 LocalDateTime，方便前端/业务层使用。
     */
    private LocalDateTime toLocalDateTime(Date date)
    {
        if (date == null)
        {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}
