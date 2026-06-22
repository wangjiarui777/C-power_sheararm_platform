package com.ruoyi.sensor.domain;

import java.util.Date;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TelemetryEnvelopeTest
{
    @Test
    void normalizeFillsIdQualityAndTimestamps()
    {
        TelemetryEnvelope envelope = new TelemetryEnvelope();
        envelope.setDeviceCode("DEV-001");
        envelope.setMetricCode("vibration");
        envelope.setValue(1.25D);

        envelope.normalize();

        assertNotNull(envelope.getEventId(), "event id");
        assertEquals("GOOD", envelope.getQuality(), "quality");
        assertNotNull(envelope.getSampleTime(), "sample time");
        assertNotNull(envelope.getReceiveTime(), "receive time");
    }

    @Test
    void normalizePreservesCollectorMetadata()
    {
        Date sampleTime = new Date(123456789L);
        TelemetryEnvelope envelope = new TelemetryEnvelope();
        envelope.setEventId("evt-1");
        envelope.setQuality("BAD");
        envelope.setSampleTime(sampleTime);

        envelope.normalize();

        assertEquals("evt-1", envelope.getEventId(), "event id");
        assertEquals("BAD", envelope.getQuality(), "quality");
        assertEquals(sampleTime, envelope.getSampleTime(), "sample time");
    }
}
