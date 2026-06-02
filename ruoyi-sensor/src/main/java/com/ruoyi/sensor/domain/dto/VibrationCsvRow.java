package com.ruoyi.sensor.domain.dto;

import java.util.Date;

public class VibrationCsvRow
{
    private final Date sampleTime;
    private final int channelId;
    private final VibrationCsvRecord record;

    public VibrationCsvRow(Date sampleTime, int channelId, VibrationCsvRecord record)
    {
        this.sampleTime = sampleTime;
        this.channelId = channelId;
        this.record = record;
    }

    public Date getSampleTime()
    {
        return sampleTime;
    }

    public int getChannelId()
    {
        return channelId;
    }

    public VibrationCsvRecord getRecord()
    {
        return record;
    }
}
