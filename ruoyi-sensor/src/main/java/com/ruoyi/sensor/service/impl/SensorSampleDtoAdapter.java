package com.ruoyi.sensor.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import com.ruoyi.sensor.domain.dto.SensorSampleDto;
import com.ruoyi.sensor.domain.dto.VibrationCsvRow;

public final class SensorSampleDtoAdapter
{
    private SensorSampleDtoAdapter()
    {
    }

    public static SensorSampleDto sample(String deviceCode, List<VibrationCsvRow> rows, Date fallbackTime)
    {
        long sampleTime = fallbackTime == null ? System.currentTimeMillis() : fallbackTime.getTime();
        double[] waveform = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++)
        {
            waveform[i] = rows.get(i).getRecord().getDeTime() == null ? 0D : rows.get(i).getRecord().getDeTime();
        }
        return new SensorSampleDto(deviceCode, sampleTime, 1000, waveform);
    }

    public static List<Double> amplitudes(List<VibrationCsvRow> rows)
    {
        List<Double> amplitudes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++)
        {
            amplitudes.add(rows.get(i).getRecord().getFaultSize() == null ? 0D : rows.get(i).getRecord().getFaultSize());
        }
        return amplitudes;
    }

    public static com.ruoyi.sensor.domain.vo.SensorFeatureVo feature(String deviceCode, VibrationCsvRow row)
    {
        LocalDateTime sampleTime = LocalDateTime.ofInstant(row.getSampleTime().toInstant(), ZoneId.systemDefault());
        Double rms = row.getRecord().getDeTime();
        Double peak = row.getRecord().getFaultSize();
        boolean alarm = peak != null && peak >= 5.0d;
        String alarmMessage = alarm ? "Acceleration exceeds alarm threshold" : "";
        return new com.ruoyi.sensor.domain.vo.SensorFeatureVo(deviceCode, row.getChannelId(), sampleTime, rms, peak, alarm, alarmMessage);
    }
}
