package com.ruoyi.sensor.domain.vo;

import java.time.LocalDateTime;

public class ChannelRealtimeVo
{
    private String deviceCode;
    private Integer channelId;
    private LocalDateTime sampleTime;
    private Double vibrationValue;
    private Double temperatureValue;
    private Double accelerationValue;
    private Double maValue;
    private Double rocValue;
    private Double rms;
    private Double peak;
    private Boolean alarm;
    private String alarmMessage;

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Integer getChannelId() { return channelId; }
    public void setChannelId(Integer channelId) { this.channelId = channelId; }
    public LocalDateTime getSampleTime() { return sampleTime; }
    public void setSampleTime(LocalDateTime sampleTime) { this.sampleTime = sampleTime; }
    public Double getVibrationValue() { return vibrationValue; }
    public void setVibrationValue(Double vibrationValue) { this.vibrationValue = vibrationValue; }
    public Double getTemperatureValue() { return temperatureValue; }
    public void setTemperatureValue(Double temperatureValue) { this.temperatureValue = temperatureValue; }
    public Double getAccelerationValue() { return accelerationValue; }
    public void setAccelerationValue(Double accelerationValue) { this.accelerationValue = accelerationValue; }
    public Double getMaValue() { return maValue; }
    public void setMaValue(Double maValue) { this.maValue = maValue; }
    public Double getRocValue() { return rocValue; }
    public void setRocValue(Double rocValue) { this.rocValue = rocValue; }
    public Double getRms() { return rms; }
    public void setRms(Double rms) { this.rms = rms; }
    public Double getPeak() { return peak; }
    public void setPeak(Double peak) { this.peak = peak; }
    public Boolean getAlarm() { return alarm; }
    public void setAlarm(Boolean alarm) { this.alarm = alarm; }
    public String getAlarmMessage() { return alarmMessage; }
    public void setAlarmMessage(String alarmMessage) { this.alarmMessage = alarmMessage; }
}
