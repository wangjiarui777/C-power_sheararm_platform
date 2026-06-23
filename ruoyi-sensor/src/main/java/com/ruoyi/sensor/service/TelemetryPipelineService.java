package com.ruoyi.sensor.service;

import java.util.Date;
import java.util.List;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.sensor.domain.dto.TelemetryAcceptance;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class TelemetryPipelineService
{
    public static final String STREAM_KEY = "monitoring:telemetry:stream";
    public static final String RETRY_STREAM_KEY = "monitoring:telemetry:retry";
    public static final String DLQ_STREAM_KEY = "monitoring:telemetry:dlq";
    public static final String GROUP = "monitoring-storage";
    private static final String DEDUPE_PREFIX = "monitoring:telemetry:event:";
    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
            + "redis.call('XADD', KEYS[2], '*', 'payload', ARGV[1], 'retry', '0') "
            + "redis.call('XTRIM', KEYS[2], 'MAXLEN', '~', ARGV[3]) "
            + "redis.call('SET', KEYS[1], '1', 'EX', ARGV[2]) "
            + "return 1",
        Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long maxLength;

    public TelemetryPipelineService(StringRedisTemplate redisTemplate,
        @Value("${sensor.stream.max-length:45000000}") long maxLength)
    {
        this.redisTemplate = redisTemplate;
        this.maxLength = Math.max(10000, maxLength);
    }

    public TelemetryAcceptance accept(TelemetryEnvelope envelope)
    {
        if (envelope == null || envelope.getDeviceCode() == null || envelope.getValue() == null)
        {
            throw new IllegalArgumentException("deviceCode and value are required");
        }
        envelope.normalize();
        Long result = redisTemplate.execute(
            ENQUEUE_SCRIPT,
            List.of(DEDUPE_PREFIX + envelope.getEventId(), STREAM_KEY),
            JSON.toJSONString(envelope),
            String.valueOf(7 * 24 * 60 * 60),
            String.valueOf(maxLength));
        if (result == null)
        {
            throw new IllegalStateException("Redis Stream enqueue returned no result");
        }
        boolean duplicate = result == 0L;
        return new TelemetryAcceptance(
            envelope.getEventId(), new Date(), duplicate, duplicate ? "DUPLICATE" : "PERSISTED");
    }

    public static TelemetryEnvelope fromUpload(String eventId, String deviceCode, String dataType,
        Integer channelId, Double value, Date sampleTime, Long sequence, String quality)
    {
        TelemetryEnvelope envelope = new TelemetryEnvelope();
        envelope.setEventId(eventId);
        envelope.setDeviceCode(deviceCode);
        envelope.setChannelId(channelId);
        envelope.setMetricCode("temperature".equals(dataType) ? "temperature" : "vibration");
        envelope.setUnit("temperature".equals(dataType) ? "℃" : "mm/s");
        envelope.setValue(value);
        envelope.setQuality(quality);
        envelope.setSampleTime(sampleTime);
        envelope.setReceiveTime(new Date());
        envelope.setSequence(sequence);
        envelope.normalize();
        return envelope;
    }
}
