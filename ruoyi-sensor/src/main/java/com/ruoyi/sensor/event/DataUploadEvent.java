package com.ruoyi.sensor.event;

import java.util.Date;

/**
 * Spring event published after a vibration or temperature data point is uploaded.
 * Listened to by {@code DataUploadEventListener} in the sensor module, which
 * pushes incremental updates to WebSocket clients subscribed to the "overview" channel.
 */
public class DataUploadEvent
{
    private final String deviceCode;
    private final String dataType;   // "vibration" or "temperature"
    private final Integer channelId;
    private final Double value;
    private final Date sampleTime;

    public DataUploadEvent(String deviceCode, String dataType, Double value, Date sampleTime)
    {
        this(deviceCode, dataType, null, value, sampleTime);
    }

    public DataUploadEvent(String deviceCode, String dataType, Integer channelId, Double value, Date sampleTime)
    {
        this.deviceCode = deviceCode;
        this.dataType = dataType;
        this.channelId = channelId;
        this.value = value;
        this.sampleTime = sampleTime;
    }

    public String getDeviceCode() { return deviceCode; }
    public String getDataType() { return dataType; }
    public Integer getChannelId() { return channelId; }
    public Double getValue() { return value; }
    public Date getSampleTime() { return sampleTime; }
}

