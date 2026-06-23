package com.ruoyi.sensor.service;

import java.nio.charset.StandardCharsets;
import com.ruoyi.sensor.domain.dto.ChannelFrameDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class RedisFrameStreamIntegrationTest
{
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect()
    {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close()
    {
        if (connectionFactory != null)
        {
            connectionFactory.destroy();
        }
    }

    @Test
    void frameIsDurableAndDuplicateFrameIdIsNotRequeued()
    {
        ChannelFramePipelineService pipeline = new ChannelFramePipelineService(redis, 1000);
        ChannelFrameDTO frame = frame("frame-durable");

        assertFalse(pipeline.accept(frame).isDuplicate());
        assertTrue(pipeline.accept(frame).isDuplicate());
        assertEquals(1L, redis.opsForStream().size(ChannelFramePipelineService.STREAM_KEY));
    }

    @Test
    void failedFrameMovesToDeadLetterAfterThreeAttempts()
    {
        ChannelFramePipelineService pipeline = new ChannelFramePipelineService(redis, 1000);
        ChannelFrameIngestService ingest = mock(ChannelFrameIngestService.class);
        doThrow(new IllegalStateException("simulated storage failure")).when(ingest).ingest(any());
        ChannelFrameStreamConsumer consumer = newConsumer(ingest);
        pipeline.accept(frame("frame-dlq"));

        consumer.consume();
        consumer.consume();
        consumer.consume();

        assertEquals(1L, redis.opsForStream().size(ChannelFramePipelineService.DLQ_STREAM_KEY));
        assertEquals(1, redis.opsForStream()
            .range(ChannelFramePipelineService.DLQ_STREAM_KEY, Range.unbounded()).size());
    }

    private ChannelFrameStreamConsumer newConsumer(ChannelFrameIngestService ingest)
    {
        try
        {
            return new ChannelFrameStreamConsumer(redis, ingest, new SimpleMeterRegistry());
        }
        catch (Exception ex)
        {
            throw new IllegalStateException(ex);
        }
    }

    private ChannelFrameDTO frame(String id)
    {
        ChannelFrameDTO frame = new ChannelFrameDTO();
        frame.setFrameId(id);
        frame.setDeviceCode("DEV-001");
        frame.setPayload("sensor_vibration_csv_st,1.0,0.5,0.5,1000,0,0,normal,0"
            .getBytes(StandardCharsets.UTF_8));
        return frame;
    }
}
