package com.ruoyi.sensor.service;

import java.nio.charset.StandardCharsets;
import com.ruoyi.sensor.domain.dto.ChannelFrameDTO;
import com.ruoyi.sensor.domain.dto.TelemetryAcceptance;
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

class ChannelFramePipelineServiceTest
{
    @Test
    void persistsAndDeduplicatesFrameIdsInRedis()
    {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
            .thenReturn(1L, 0L);
        ChannelFramePipelineService service = new ChannelFramePipelineService(redis, 1000);
        ChannelFrameDTO frame = frame();

        TelemetryAcceptance accepted = service.accept(frame);
        TelemetryAcceptance duplicate = service.accept(frame);

        assertFalse(accepted.isDuplicate());
        assertEquals("PERSISTED", accepted.getQueueStatus());
        assertTrue(duplicate.isDuplicate());
        assertEquals("DUPLICATE", duplicate.getQueueStatus());
    }

    @Test
    void redisFailureCannotBeReportedAsAccepted()
    {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class,
            () -> new ChannelFramePipelineService(redis, 1000).accept(frame()));
    }

    private ChannelFrameDTO frame()
    {
        ChannelFrameDTO frame = new ChannelFrameDTO();
        frame.setFrameId("frame-1");
        frame.setDeviceCode("DEV-001");
        frame.setPayload("sample".getBytes(StandardCharsets.UTF_8));
        return frame;
    }
}
