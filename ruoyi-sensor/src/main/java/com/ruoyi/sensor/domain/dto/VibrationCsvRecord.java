package com.ruoyi.sensor.domain.dto;

import com.ruoyi.common.domain.dto.VibrationCsvProtocol;

public class VibrationCsvRecord
{
    private final VibrationCsvProtocol protocol;

    public VibrationCsvRecord(VibrationCsvProtocol protocol)
    {
        this.protocol = protocol;
    }

    public String getHeader()
    {
        return protocol.getHeader();
    }

    public String getVersion()
    {
        return protocol.getVersion();
    }

    public String getGlobals()
    {
        return protocol.getGlobals();
    }

    public Double getDeTime()
    {
        return protocol.getDeTime();
    }

    public Double getSampleRate()
    {
        return protocol.getSampleRate();
    }

    public Double getRpm()
    {
        return protocol.getRpm();
    }

    public Double getLoad()
    {
        return protocol.getLoad();
    }

    public String getFaultType()
    {
        return protocol.getFaultType();
    }

    public Double getFaultSize()
    {
        return protocol.getFaultSize();
    }

    public static VibrationCsvRecord parse(String line)
    {
        return new VibrationCsvRecord(VibrationCsvProtocol.parse(line));
    }
}
