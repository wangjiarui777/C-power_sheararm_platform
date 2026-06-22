package com.ruoyi.sensor.domain.dto;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;
import lombok.Data;

/**
 * Canonical telemetry contract shared by every acquisition adapter.
 */
@Data
public class TelemetryEnvelope implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String eventId;
    private String deviceCode;
    private Long pointId;
    private String pointCode;
    private Integer channelId;
    private String metricCode;
    private String signalType;
    private String source;
    private Double value;
    private String unit;
    private String quality;
    private Date sampleTime;
    private Date receiveTime;
    private Long sequence;

    public void normalize()
    {
        if (eventId == null || eventId.trim().isEmpty())
        {
            eventId = UUID.randomUUID().toString();
        }
        if (quality == null || quality.trim().isEmpty())
        {
            quality = "GOOD";
        }
        if (sampleTime == null)
        {
            sampleTime = new Date();
        }
        if (receiveTime == null)
        {
            receiveTime = new Date();
        }
        if (signalType == null || signalType.trim().isEmpty())
        {
            signalType = "temperature".equals(metricCode) ? "temperature" : "vibration";
        }
    }
}
