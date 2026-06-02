package com.ruoyi.sensor.domain.vo;

import java.time.LocalDateTime;
import java.util.List;

public class SensorChannelRealtimeVo
{
    private String deviceCode;
    private Integer channelId;
    private LocalDateTime sampleTime;
    private Double vibrationValue;
    private Double temperatureValue;
    private Double rms;
    private Double peak;
    private Double crestFactor;
    private Double kurtosis;
    private Double centroidFrequency;
    private Double rmsFrequency;
    private Boolean alarm;
    private String alarmMessage;
    private List<Double> spectrum;
    private List<Double> frequencyAxis;

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
    public Double getRms() { return rms; }
    public void setRms(Double rms) { this.rms = rms; }
    public Double getPeak() { return peak; }
    public void setPeak(Double peak) { this.peak = peak; }
    public Double getCrestFactor() { return crestFactor; }
    public void setCrestFactor(Double crestFactor) { this.crestFactor = crestFactor; }
    public Double getKurtosis() { return kurtosis; }
    public void setKurtosis(Double kurtosis) { this.kurtosis = kurtosis; }
    public Double getCentroidFrequency() { return centroidFrequency; }
    public void setCentroidFrequency(Double centroidFrequency) { this.centroidFrequency = centroidFrequency; }
    public Double getRmsFrequency() { return rmsFrequency; }
    public void setRmsFrequency(Double rmsFrequency) { this.rmsFrequency = rmsFrequency; }
    public Boolean getAlarm() { return alarm; }
    public void setAlarm(Boolean alarm) { this.alarm = alarm; }
    public String getAlarmMessage() { return alarmMessage; }
    public void setAlarmMessage(String alarmMessage) { this.alarmMessage = alarmMessage; }
    public List<Double> getSpectrum() { return spectrum; }
    public void setSpectrum(List<Double> spectrum) { this.spectrum = spectrum; }
    public List<Double> getFrequencyAxis() { return frequencyAxis; }
    public void setFrequencyAxis(List<Double> frequencyAxis) { this.frequencyAxis = frequencyAxis; }
}
