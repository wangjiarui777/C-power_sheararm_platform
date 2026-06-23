package com.ruoyi.sensor.service;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.sensor.domain.dto.ChannelFrameDTO;
import com.ruoyi.sensor.domain.dto.TelemetryAcceptance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class ChannelFramePipelineService
{
    public static final String STREAM_KEY = "monitoring:vibration-frame:stream";
    public static final String DLQ_STREAM_KEY = "monitoring:vibration-frame:dlq";
    public static final String GROUP = "monitoring-frame-storage";
    private static final String DEDUPE_PREFIX = "monitoring:vibration-frame:id:";
    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
            + "redis.call('XADD', KEYS[2], '*', 'payload', ARGV[1], 'retry', '0') "
            + "redis.call('XTRIM', KEYS[2], 'MAXLEN', '~', ARGV[3]) "
            + "redis.call('SET', KEYS[1], '1', 'EX', ARGV[2]) "
            + "return 1",
        Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long maxLength;

    public ChannelFramePipelineService(StringRedisTemplate redisTemplate,
        @Value("${sensor.frame-stream.max-length:100000}") long maxLength)
    {
        this.redisTemplate = redisTemplate;
        this.maxLength = Math.max(1000, maxLength);
    }

    public TelemetryAcceptance accept(ChannelFrameDTO frame)
    {
        if (frame == null || blank(frame.getDeviceCode()) || frame.getPayload() == null
            || frame.getPayload().length == 0)
        {
            throw new IllegalArgumentException("deviceCode and frame payload are required");
        }
        if (blank(frame.getFrameId()))
        {
            frame.setFrameId(UUID.randomUUID().toString());
        }
        if (blank(frame.getSchemaVersion()))
        {
            frame.setSchemaVersion("VibrationFrameEnvelope/v1");
        }
        if (frame.getCollectTime() == null)
        {
            frame.setCollectTime(new Date());
        }
        Long result = redisTemplate.execute(
            ENQUEUE_SCRIPT,
            List.of(DEDUPE_PREFIX + frame.getFrameId(), STREAM_KEY),
            JSON.toJSONString(frame),
            String.valueOf(7 * 24 * 60 * 60),
            String.valueOf(maxLength));
        if (result == null)
        {
            throw new IllegalStateException("Redis frame stream enqueue returned no result");
        }
        boolean duplicate = result == 0L;
        return new TelemetryAcceptance(frame.getFrameId(), new Date(), duplicate,
            duplicate ? "DUPLICATE" : "PERSISTED");
    }

    private boolean blank(String value)
    {
        return value == null || value.isBlank();
    }
}
