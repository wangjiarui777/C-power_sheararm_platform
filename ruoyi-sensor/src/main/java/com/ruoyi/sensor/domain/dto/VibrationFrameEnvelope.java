package com.ruoyi.sensor.domain.dto;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class VibrationFrameEnvelope implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String frameId;
    private String deviceCode;
    private Long pointId;
    private String pointCode;
    private Integer channelId;
    private Integer sampleRate;
    private Integer sampleCount;
    private List<Double> waveform;
    private List<Double> spectrum;
    private Double freqStep;
    private Double rpm;
    private Double load;
    private String faultType;
    private Double faultSize;
    private String quality;
    private String axis;
    private String unit;
    private Date sampleTime;
    private Date receiveTime;
    private Long sequence;

    public void normalize()
    {
        if (frameId == null || frameId.trim().isEmpty())
        {
            frameId = UUID.randomUUID().toString();
        }
        if (quality == null || quality.trim().isEmpty())
        {
            quality = "GOOD";
        }
        if (unit == null || unit.trim().isEmpty())
        {
            unit = "mm/s";
        }
        if (sampleTime == null)
        {
            sampleTime = new Date();
        }
        if (receiveTime == null)
        {
            receiveTime = new Date();
        }
        if (sampleCount == null && waveform != null)
        {
            sampleCount = waveform.size();
        }
        if (sequence == null)
        {
            sequence = sampleTime.getTime();
        }
    }

    public String getFrameId()
    {
        return frameId;
    }

    public void setFrameId(String frameId)
    {
        this.frameId = frameId;
    }

    public String getDeviceCode()
    {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode)
    {
        this.deviceCode = deviceCode;
    }

    public Long getPointId()
    {
        return pointId;
    }

    public void setPointId(Long pointId)
    {
        this.pointId = pointId;
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

    public String getAxis()
    {
        return axis;
    }

    public void setAxis(String axis)
    {
        this.axis = axis;
    }

    public String getUnit()
    {
        return unit;
    }

    public void setUnit(String unit)
    {
        this.unit = unit;
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
