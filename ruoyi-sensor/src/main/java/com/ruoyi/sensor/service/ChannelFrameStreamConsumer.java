package com.ruoyi.sensor.service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.sensor.domain.dto.ChannelFrameDTO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.MDC;

@Service
public class ChannelFrameStreamConsumer
{
    private static final int MAX_RETRIES = 3;
    private final StringRedisTemplate redisTemplate;
    private final ChannelFrameIngestService ingestService;
    private final String consumerName;
    private final Counter processedCounter;
    private final Counter retryCounter;
    private final Counter deadLetterCounter;

    public ChannelFrameStreamConsumer(StringRedisTemplate redisTemplate,
        ChannelFrameIngestService ingestService, MeterRegistry meterRegistry) throws Exception
    {
        this.redisTemplate = redisTemplate;
        this.ingestService = ingestService;
        this.consumerName = InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        this.processedCounter = meterRegistry.counter("phm.vibration_frame.processed");
        this.retryCounter = meterRegistry.counter("phm.vibration_frame.retry");
        this.deadLetterCounter = meterRegistry.counter("phm.vibration_frame.dead_letter");
        Gauge.builder("phm.vibration_frame.stream.length", redisTemplate,
            template -> streamSize(template, ChannelFramePipelineService.STREAM_KEY))
            .register(meterRegistry);
        Gauge.builder("phm.vibration_frame.dead_letter.length", redisTemplate,
            template -> streamSize(template, ChannelFramePipelineService.DLQ_STREAM_KEY))
            .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${sensor.frame-stream.poll-delay-ms:500}")
    public void consume()
    {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
            Consumer.from(ChannelFramePipelineService.GROUP, consumerName),
            StreamReadOptions.empty().count(20).block(Duration.ofSeconds(1)),
            StreamOffset.create(ChannelFramePipelineService.STREAM_KEY, ReadOffset.lastConsumed()));
        if (records != null)
        {
            records.forEach(this::handle);
        }
    }

    private void handle(MapRecord<String, Object, Object> record)
    {
        String payload = String.valueOf(record.getValue().get("payload"));
        int retry = parseRetry(record.getValue().get("retry"));
        try
        {
            ChannelFrameDTO frame = JSON.parseObject(payload, ChannelFrameDTO.class);
            MDC.put("eventId", frame.getFrameId());
            MDC.put("deviceCode", frame.getDeviceCode());
            ingestService.ingest(frame);
            acknowledge(record.getId());
            redisTemplate.opsForValue().increment("monitoring:vibration-frame:processed");
            processedCounter.increment();
        }
        catch (Exception ex)
        {
            int nextRetry = retry + 1;
            String target = nextRetry >= MAX_RETRIES
                ? ChannelFramePipelineService.DLQ_STREAM_KEY : ChannelFramePipelineService.STREAM_KEY;
            redisTemplate.opsForStream().add(target, Map.of(
                "payload", payload,
                "retry", String.valueOf(nextRetry),
                "error", ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage())));
            acknowledge(record.getId());
            if (nextRetry >= MAX_RETRIES)
            {
                deadLetterCounter.increment();
            }
            else
            {
                retryCounter.increment();
            }
        }
        finally
        {
            MDC.remove("eventId");
            MDC.remove("deviceCode");
        }
    }

    private void ensureGroup()
    {
        try
        {
            redisTemplate.opsForStream().createGroup(
                ChannelFramePipelineService.STREAM_KEY, ReadOffset.from("0-0"),
                ChannelFramePipelineService.GROUP);
        }
        catch (Exception ignored)
        {
            // BUSYGROUP means the group already exists.
        }
    }

    private void acknowledge(RecordId id)
    {
        redisTemplate.opsForStream().acknowledge(
            ChannelFramePipelineService.STREAM_KEY, ChannelFramePipelineService.GROUP, id);
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

    private static double streamSize(StringRedisTemplate template, String key)
    {
        try
        {
            Long size = template.opsForStream().size(key);
            return size == null ? 0D : size.doubleValue();
        }
        catch (Exception ignored)
        {
            return Double.NaN;
        }
    }
}
