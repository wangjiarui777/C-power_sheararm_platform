package com.ruoyi.sensor.service;

import java.util.Date;
import com.ruoyi.sensor.domain.dto.TelemetryAcceptance;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryPipelineServiceTest
{
    @Test
    void reportsPersistedAndDuplicateQueueStates()
    {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
            .thenReturn(1L, 0L);
        TelemetryPipelineService service = new TelemetryPipelineService(redis, 10000);
        TelemetryEnvelope envelope = envelope();

        TelemetryAcceptance first = service.accept(envelope);
        TelemetryAcceptance duplicate = service.accept(envelope);

        assertFalse(first.isDuplicate());
        assertEquals("PERSISTED", first.getQueueStatus());
        assertTrue(duplicate.isDuplicate());
        assertEquals("DUPLICATE", duplicate.getQueueStatus());
    }

    @Test
    void redisFailureDoesNotPretendTheUploadWasAccepted()
    {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class,
            () -> new TelemetryPipelineService(redis, 10000).accept(envelope()));
    }

    private TelemetryEnvelope envelope()
    {
        return TelemetryPipelineService.fromUpload(
            "event-1", "DEV-001", "vibration", 1, 1.2, new Date(), 1L, "GOOD");
    }
}
