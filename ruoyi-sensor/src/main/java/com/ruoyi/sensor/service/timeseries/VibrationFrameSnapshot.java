package com.ruoyi.sensor.service.timeseries;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class VibrationFrameSnapshot
{
    private String deviceCode;
    private String pointCode;
    private Integer channelId;
    private Integer sampleRate;
    private Integer sampleCount;
    private List<Double> waveform = new ArrayList<>();
    private List<Double> spectrum = new ArrayList<>();
    private Double freqStep;
    private Integer fftSize;
    private Double rpm;
    private Double load;
    private String faultType;
    private Double faultSize;
    private String quality;
    private Date sampleTime;
    private Date receiveTime;
    private Long sequence;

    public String getDeviceCode()
    {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode)
    {
        this.deviceCode = deviceCode;
    }

    public String getPointCode()
    {
        return pointCode;
    }

    public void setPointCode(String pointCode)
    {
        this.pointCode = pointCode;
    }

    public Integer getChannelId()
    {
        return channelId;
    }

    public void setChannelId(Integer channelId)
    {
        this.channelId = channelId;
    }

    public Integer getSampleRate()
    {
        return sampleRate;
    }

    public void setSampleRate(Integer sampleRate)
    {
        this.sampleRate = sampleRate;
    }

    public Integer getSampleCount()
    {
        return sampleCount;
    }

    public void setSampleCount(Integer sampleCount)
    {
        this.sampleCount = sampleCount;
    }

    public List<Double> getWaveform()
    {
        return waveform;
    }

    public void setWaveform(List<Double> waveform)
    {
        this.waveform = waveform;
    }

    public List<Double> getSpectrum()
    {
        return spectrum;
    }

    public void setSpectrum(List<Double> spectrum)
    {
        this.spectrum = spectrum;
    }

    public Double getFreqStep()
    {
        return freqStep;
    }

    public void setFreqStep(Double freqStep)
    {
        this.freqStep = freqStep;
    }

    public Integer getFftSize()
    {
        return fftSize;
    }

    public void setFftSize(Integer fftSize)
    {
        this.fftSize = fftSize;
    }

    public Double getRpm()
    {
        return rpm;
    }

    public void setRpm(Double rpm)
    {
        this.rpm = rpm;
    }

    public Double getLoad()
    {
        return load;
    }

    public void setLoad(Double load)
    {
        this.load = load;
    }

    public String getFaultType()
    {
        return faultType;
    }

    public void setFaultType(String faultType)
    {
        this.faultType = faultType;
    }

    public Double getFaultSize()
    {
        return faultSize;
    }

    public void setFaultSize(Double faultSize)
    {
        this.faultSize = faultSize;
    }

    public String getQuality()
    {
        return quality;
    }

    public void setQuality(String quality)
    {
        this.quality = quality;
    }

    public Date getSampleTime()
    {
        return sampleTime;
    }

    public void setSampleTime(Date sampleTime)
    {
        this.sampleTime = sampleTime;
    }

    public Date getReceiveTime()
    {
        return receiveTime;
    }

    public void setReceiveTime(Date receiveTime)
    {
        this.receiveTime = receiveTime;
    }

    public Long getSequence()
    {
        return sequence;
    }

    public void setSequence(Long sequence)
    {
        this.sequence = sequence;
    }
}
