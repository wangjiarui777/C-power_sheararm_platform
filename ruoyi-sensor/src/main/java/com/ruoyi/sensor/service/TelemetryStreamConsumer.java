package com.ruoyi.sensor.service;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.sensor.domain.DeviceTemperatureData;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;
import com.ruoyi.sensor.websocket.SensorWebSocketHandler;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

@Service
public class TelemetryStreamConsumer
{
    private static final int MAX_RETRIES = 3;

    private final StringRedisTemplate redisTemplate;
    private final IDeviceVibrationDataService vibrationService;
    private final IDeviceTemperatureDataService temperatureService;
    private final TimeSeriesStore timeSeriesStore;
    private final PhmService phmService;
    private final String consumerName;

    public TelemetryStreamConsumer(StringRedisTemplate redisTemplate,
        IDeviceVibrationDataService vibrationService,
        IDeviceTemperatureDataService temperatureService,
        TimeSeriesStore timeSeriesStore,
        PhmService phmService) throws Exception
    {
        this.redisTemplate = redisTemplate;
        this.vibrationService = vibrationService;
        this.temperatureService = temperatureService;
        this.timeSeriesStore = timeSeriesStore;
        this.phmService = phmService;
        this.consumerName = InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
    }

    @Scheduled(fixedDelayString = "${sensor.stream.poll-delay-ms:500}")
    public void consume()
    {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
            Consumer.from(TelemetryPipelineService.GROUP, consumerName),
            StreamReadOptions.empty().count(100).block(Duration.ofSeconds(1)),
            StreamOffset.create(TelemetryPipelineService.STREAM_KEY, ReadOffset.lastConsumed()));
        if (records == null)
        {
            return;
        }
        for (MapRecord<String, Object, Object> record : records)
        {
            handle(record);
        }
    }

    private void handle(MapRecord<String, Object, Object> record)
    {
        String payload = String.valueOf(record.getValue().get("payload"));
        int retry = parseRetry(record.getValue().get("retry"));
        try
        {
            TelemetryEnvelope envelope = JSON.parseObject(payload, TelemetryEnvelope.class);
            persist(envelope);
            acknowledge(record.getId());
            redisTemplate.opsForValue().increment("monitoring:telemetry:processed");
        }
        catch (Exception ex)
        {
            Map<String, String> next = Map.of(
                "payload", payload,
                "retry", String.valueOf(retry + 1),
                "error", ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
            String target = retry + 1 >= MAX_RETRIES
                ? TelemetryPipelineService.DLQ_STREAM_KEY : TelemetryPipelineService.STREAM_KEY;
            redisTemplate.opsForStream().add(target, next);
            acknowledge(record.getId());
            redisTemplate.opsForValue().increment(retry + 1 >= MAX_RETRIES
                ? "monitoring:telemetry:dead-letter" : "monitoring:telemetry:retry-count");
        }
    }

    private void persist(TelemetryEnvelope envelope)
    {
        if ("temperature".equals(envelope.getMetricCode()))
        {
            DeviceTemperatureData data = new DeviceTemperatureData();
            data.setEventId(envelope.getEventId());
            data.setDeviceCode(envelope.getDeviceCode());
            data.setPointId(envelope.getPointId());
            data.setChannelId(envelope.getChannelId());
            data.setTemperatureValue(BigDecimal.valueOf(envelope.getValue()));
            data.setCollectionTime(envelope.getSampleTime());
            data.setReceiveTime(envelope.getReceiveTime());
            data.setQuality(envelope.getQuality());
            data.setCreateBy("collector-stream");
            try
            {
                temperatureService.insertDeviceTemperatureData(data);
            }
            catch (DuplicateKeyException ignored)
            {
                // A retry with the same eventId is already durable in MySQL.
            }
        }
        else
        {
            DeviceVibrationData data = new DeviceVibrationData();
            data.setEventId(envelope.getEventId());
            data.setDeviceCode(envelope.getDeviceCode());
            data.setPointId(envelope.getPointId());
            data.setChannelId(envelope.getChannelId());
            data.setVibrationValue(BigDecimal.valueOf(envelope.getValue()));
            data.setSampleTime(envelope.getSampleTime());
            data.setReceiveTime(envelope.getReceiveTime());
            data.setQuality(envelope.getQuality());
            data.setCreateBy("collector-stream");
            try
            {
                vibrationService.insertDeviceVibrationData(data);
            }
            catch (DuplicateKeyException ignored)
            {
                // A retry with the same eventId is already durable in MySQL.
            }
        }
        timeSeriesStore.writeTelemetry(envelope);
        phmService.evaluateUpload(envelope.getDeviceCode(), envelope.getMetricCode(),
            envelope.getChannelId(), envelope.getValue(), envelope.getSampleTime());
        SensorWebSocketHandler.broadcastTelemetry(envelope);
    }

    private void ensureGroup()
    {
        try
        {
            redisTemplate.opsForStream().createGroup(
                TelemetryPipelineService.STREAM_KEY, ReadOffset.from("0-0"), TelemetryPipelineService.GROUP);
        }
        catch (Exception ignored)
        {
            // BUSYGROUP means the group already exists.
        }
    }

    private void acknowledge(RecordId id)
    {
        redisTemplate.opsForStream().acknowledge(
            TelemetryPipelineService.STREAM_KEY, TelemetryPipelineService.GROUP, id);
    }

    private int parseRetry(Object value)
    {
        try
        {
            return Integer.parseInt(String.valueOf(value));
        }
        catch (Exception ignored)
        {
            return 0;
        }
    }
}
