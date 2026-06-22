package com.ruoyi.sensor.service;

import java.sql.Timestamp;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;
import com.ruoyi.sensor.websocket.SensorWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Service;

/**
 * Lightweight telemetry pipeline built on the platform's existing Redis.
 * Database persistence still happens in the acquisition adapter; this service
 * provides idempotency, stream fan-out, latest-state caching, rule evaluation,
 * and realtime notification.
 */
@Service
public class TelemetryPipelineService
{
    private static final String STREAM_KEY = "monitoring:telemetry:stream";
    private static final String DEDUPE_PREFIX = "monitoring:telemetry:event:";
    private static final String LATEST_PREFIX = "monitoring:latest:";

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private PhmService phmService;

    @Autowired
    private TimeSeriesStore timeSeriesStore;

    public boolean accept(TelemetryEnvelope envelope)
    {
        if (envelope == null || envelope.getDeviceCode() == null || envelope.getValue() == null)
        {
            return false;
        }
        envelope.normalize();
        if (!claimEvent(envelope.getEventId()))
        {
            return false;
        }
        appendStream(envelope);
        cacheLatest(envelope);
        timeSeriesStore.writeTelemetry(envelope);
        phmService.evaluateUpload(
                envelope.getDeviceCode(),
                "temperature".equals(envelope.getMetricCode()) ? "temperature" : "vibration",
                envelope.getChannelId(),
                envelope.getValue(),
                envelope.getSampleTime());
        SensorWebSocketHandler.broadcastTelemetry(envelope);
        return true;
    }

    private boolean claimEvent(String eventId)
    {
        try
        {
            Boolean claimed = redisCache.redisTemplate.opsForValue()
                    .setIfAbsent(DEDUPE_PREFIX + eventId, "1", 24, TimeUnit.HOURS);
            return Boolean.TRUE.equals(claimed);
        }
        catch (Exception ignored)
        {
            // Redis failure must not block acquisition. Database uniqueness and
            // active-alarm coalescing remain the fallback safeguards.
            return true;
        }
    }

    private void appendStream(TelemetryEnvelope envelope)
    {
        try
        {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("eventId", envelope.getEventId());
            body.put("deviceCode", envelope.getDeviceCode());
            body.put("pointId", String.valueOf(envelope.getPointId()));
            body.put("pointCode", String.valueOf(envelope.getPointCode()));
            body.put("channelId", String.valueOf(envelope.getChannelId()));
            body.put("metricCode", envelope.getMetricCode());
            body.put("value", String.valueOf(envelope.getValue()));
            body.put("unit", String.valueOf(envelope.getUnit()));
            body.put("quality", envelope.getQuality());
            body.put("sampleTime", String.valueOf(envelope.getSampleTime().getTime()));
            body.put("receiveTime", String.valueOf(envelope.getReceiveTime().getTime()));
            body.put("sequence", String.valueOf(envelope.getSequence()));
            redisCache.redisTemplate.opsForStream()
                    .add(StreamRecords.newRecord().ofMap(body).withStreamKey(STREAM_KEY));
        }
        catch (Exception ignored)
        {
            // Stream buffering is best-effort while Redis is unavailable.
        }
    }

    private void cacheLatest(TelemetryEnvelope envelope)
    {
        try
        {
            String pointKey = envelope.getPointId() == null
                    ? "ch-" + String.valueOf(envelope.getChannelId())
                    : String.valueOf(envelope.getPointId());
            redisCache.setCacheObject(
                    LATEST_PREFIX + envelope.getDeviceCode() + ":" + pointKey + ":" + envelope.getMetricCode(),
                    JSON.toJSONString(envelope),
                    10,
                    TimeUnit.MINUTES);
        }
        catch (Exception ignored)
        {
        }
    }

    public static TelemetryEnvelope fromUpload(String deviceCode, String dataType, Integer channelId,
                                               Double value, Date sampleTime)
    {
        TelemetryEnvelope envelope = new TelemetryEnvelope();
        envelope.setDeviceCode(deviceCode);
        envelope.setChannelId(channelId);
        envelope.setMetricCode("temperature".equals(dataType) ? "temperature" : "vibration");
        envelope.setUnit("temperature".equals(dataType) ? "℃" : "mm/s");
        envelope.setValue(value);
        envelope.setQuality("GOOD");
        envelope.setSampleTime(sampleTime);
        envelope.setReceiveTime(new Date());
        envelope.setSequence(sampleTime == null ? System.currentTimeMillis() : sampleTime.getTime());
        envelope.normalize();
        return envelope;
    }
}
