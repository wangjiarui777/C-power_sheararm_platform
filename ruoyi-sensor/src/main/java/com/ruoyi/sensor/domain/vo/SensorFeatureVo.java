package com.ruoyi.sensor.domain.vo;

import java.time.LocalDateTime;

public class SensorFeatureVo
{
    private String deviceCode;
    private Integer channelId;
    private LocalDateTime sampleTime;
    private Double rms;
    private Double peak;
    private Boolean alarm;
    private String alarmMessage;

    public SensorFeatureVo()
    {
    }

    public SensorFeatureVo(String deviceCode, Integer channelId, LocalDateTime sampleTime, Double rms, Double peak, Boolean alarm, String alarmMessage)
    {
        this.deviceCode = deviceCode;
        this.channelId = channelId;
        this.sampleTime = sampleTime;
        this.rms = rms;
        this.peak = peak;
        this.alarm = alarm;
        this.alarmMessage = alarmMessage;
    }

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Integer getChannelId() { return channelId; }
    public void setChannelId(Integer channelId) { this.channelId = channelId; }
    public LocalDateTime getSampleTime() { return sampleTime; }
    public void setSampleTime(LocalDateTime sampleTime) { this.sampleTime = sampleTime; }
    public Double getRms() { return rms; }
    public void setRms(Double rms) { this.rms = rms; }
    public Double getPeak() { return peak; }
    public void setPeak(Double peak) { this.peak = peak; }
    public Boolean getAlarm() { return alarm; }
    public void setAlarm(Boolean alarm) { this.alarm = alarm; }
    public String getAlarmMessage() { return alarmMessage; }
    public void setAlarmMessage(String alarmMessage) { this.alarmMessage = alarmMessage; }
}
