package com.ruoyi.sensor.domain.entity;

import java.util.Date;

public class SensorRawWaveEntity
{
    private String deviceCode;
    private Integer channelId;
    private Date sampleTime;
    private Double voltageValue;
    private Double accelerationValue;
    private Date createTime;

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Integer getChannelId() { return channelId; }
    public void setChannelId(Integer channelId) { this.channelId = channelId; }
    public Date getSampleTime() { return sampleTime; }
    public void setSampleTime(Date sampleTime) { this.sampleTime = sampleTime; }
    public Double getVoltageValue() { return voltageValue; }
    public void setVoltageValue(Double voltageValue) { this.voltageValue = voltageValue; }
    public Double getAccelerationValue() { return accelerationValue; }
    public void setAccelerationValue(Double accelerationValue) { this.accelerationValue = accelerationValue; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
