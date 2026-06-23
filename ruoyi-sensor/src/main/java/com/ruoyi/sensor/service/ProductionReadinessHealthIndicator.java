package com.ruoyi.sensor.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import com.ruoyi.sensor.service.impl.SensorTcpServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component("phmProductionReadiness")
public class ProductionReadinessHealthIndicator implements HealthIndicator
{
    private final StringRedisTemplate redisTemplate;
    private final SensorTcpServer tcpServer;
    private final Path attachmentRoot;
    private final String inferenceHealthUrl;
    private final String inferenceToken;
    private final boolean inferenceRequired;
    private final RestClient restClient;

    public ProductionReadinessHealthIndicator(StringRedisTemplate redisTemplate,
        SensorTcpServer tcpServer,
        @Value("${sensor.attachment.root:D:/ruoyi-secure/attachments}") String attachmentRoot,
        @Value("${sensor.inference.gear-url:}") String inferenceUrl,
        @Value("${sensor.inference.internal-token:}") String inferenceToken,
        @Value("${sensor.health.inference-required:false}") boolean inferenceRequired)
    {
        this.redisTemplate = redisTemplate;
        this.tcpServer = tcpServer;
        this.attachmentRoot = Path.of(attachmentRoot).toAbsolutePath().normalize();
        this.inferenceHealthUrl = inferenceUrl == null ? ""
            : inferenceUrl.replaceAll("/internal/infer/?$", "/internal/health/ready");
        this.inferenceToken = inferenceToken;
        this.inferenceRequired = inferenceRequired;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Health health()
    {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean up = true;
        try
        {
            details.put("telemetryStreamLength", size(TelemetryPipelineService.STREAM_KEY));
            details.put("telemetryDeadLetterLength", size(TelemetryPipelineService.DLQ_STREAM_KEY));
            details.put("telemetryOldestAgeSeconds", oldestAge(TelemetryPipelineService.STREAM_KEY));
            details.put("frameStreamLength", size(ChannelFramePipelineService.STREAM_KEY));
            details.put("frameDeadLetterLength", size(ChannelFramePipelineService.DLQ_STREAM_KEY));
            details.put("frameOldestAgeSeconds", oldestAge(ChannelFramePipelineService.STREAM_KEY));
        }
        catch (Exception ex)
        {
            details.put("redisStreams", "unavailable: " + ex.getClass().getSimpleName());
            up = false;
        }
        try
        {
            Files.createDirectories(attachmentRoot);
            long total = attachmentRoot.toFile().getTotalSpace();
            long usable = attachmentRoot.toFile().getUsableSpace();
            details.put("attachmentDiskUsableBytes", usable);
            details.put("attachmentDiskUsedPercent", total <= 0 ? null
                : Math.round((1D - usable / (double) total) * 1000D) / 10D);
            if (total > 0 && usable / (double) total < 0.10D)
            {
                up = false;
            }
        }
        catch (Exception ex)
        {
            details.put("attachmentStorage", "unavailable");
            up = false;
        }
        details.put("tcpEnabled", tcpServer.isEnabled());
        details.put("tcpListening", tcpServer.isListening());
        if (tcpServer.isEnabled() && !tcpServer.isListening())
        {
            up = false;
        }
        if (inferenceRequired)
        {
            try
            {
                Map<?, ?> response = restClient.get().uri(inferenceHealthUrl)
                    .header("X-Internal-Token", inferenceToken)
                    .retrieve().body(Map.class);
                details.put("inference", response == null ? "empty" : response.get("status"));
            }
            catch (Exception ex)
            {
                details.put("inference", "unavailable");
                up = false;
            }
        }
        return (up ? Health.up() : Health.down()).withDetails(details).build();
    }

    private long size(String key)
    {
        Long value = redisTemplate.opsForStream().size(key);
        return value == null ? 0L : value;
    }

    private long oldestAge(String key)
    {
        java.util.List<MapRecord<String, Object, Object>> rows = redisTemplate.opsForStream()
            .range(key, Range.unbounded(), Limit.limit().count(1));
        if (rows == null || rows.isEmpty())
        {
            return 0L;
        }
        long createdAt = rows.get(0).getId().getTimestamp();
        return Math.max(0L, Duration.between(Instant.ofEpochMilli(createdAt), Instant.now()).toSeconds());
    }
}
