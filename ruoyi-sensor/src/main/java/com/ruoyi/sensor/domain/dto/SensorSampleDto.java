package com.ruoyi.sensor.domain.dto;

public class SensorSampleDto
{
    private String deviceCode;
    private long sampleTime;
    private int sampleRate;
    private double[] waveform;

    public SensorSampleDto()
    {
    }

    public SensorSampleDto(String deviceCode, long sampleTime, int sampleRate, double[] waveform)
    {
        this.deviceCode = deviceCode;
        this.sampleTime = sampleTime;
        this.sampleRate = sampleRate;
        this.waveform = waveform;
    }

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public long getSampleTime() { return sampleTime; }
    public void setSampleTime(long sampleTime) { this.sampleTime = sampleTime; }
    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    public double[] getWaveform() { return waveform; }
    public void setWaveform(double[] waveform) { this.waveform = waveform; }
}
