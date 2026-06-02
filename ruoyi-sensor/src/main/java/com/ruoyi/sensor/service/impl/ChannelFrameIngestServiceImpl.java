package com.ruoyi.sensor.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.ruoyi.common.domain.dto.VibrationCsvProtocol;
import com.ruoyi.sensor.domain.dto.ChannelFrameDTO;
import com.ruoyi.sensor.domain.dto.VibrationCsvRecord;
import com.ruoyi.sensor.domain.dto.VibrationCsvRow;
import com.ruoyi.sensor.domain.entity.SensorRawWaveEntity;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.service.ChannelFrameIngestService;
import com.ruoyi.sensor.service.SensorStorageService;
import com.ruoyi.sensor.service.SensorWebSocketPushService;
import com.ruoyi.sensor.tdengine.SensorTdengineWriter;

@Service
public class ChannelFrameIngestServiceImpl implements ChannelFrameIngestService
{
    private static final int MAX_BATCH_LINES = 2000;

    private final SensorTdengineWriter tdengineWriter;
    private final SensorStorageService storageService;
    private final SensorWebSocketPushService webSocketPushService;

    public ChannelFrameIngestServiceImpl(SensorTdengineWriter tdengineWriter,
                                         SensorStorageService storageService,
                                         SensorWebSocketPushService webSocketPushService)
    {
        this.tdengineWriter = tdengineWriter;
        this.storageService = storageService;
        this.webSocketPushService = webSocketPushService;
    }

    @Override
    public void ingest(ChannelFrameDTO dto)
    {
        List<VibrationCsvRow> rows = parseBatch(dto);
        if (rows.isEmpty())
        {
            return;
        }

        List<SensorRawWaveEntity> rawEntities = new ArrayList<>();
        for (VibrationCsvRow row : rows)
        {
            SensorRawWaveEntity entity = new SensorRawWaveEntity();
            entity.setDeviceCode(dto.getDeviceCode());
            entity.setChannelId(row.getChannelId());
            entity.setSampleTime(row.getSampleTime());
            entity.setVoltageValue(row.getRecord().getDeTime());
            entity.setAccelerationValue(row.getRecord().getFaultSize());
            entity.setCreateTime(new Date());
            rawEntities.add(entity);

            ChannelRealtimeVo realtimeVo = new ChannelRealtimeVo();
            realtimeVo.setDeviceCode(dto.getDeviceCode());
            realtimeVo.setChannelId(row.getChannelId());
            realtimeVo.setSampleTime(java.time.LocalDateTime.ofInstant(row.getSampleTime().toInstant(), java.time.ZoneId.systemDefault()));
            realtimeVo.setVibrationValue(row.getRecord().getDeTime());
            realtimeVo.setAccelerationValue(row.getRecord().getFaultSize());
            realtimeVo.setRms(row.getRecord().getDeTime());
            realtimeVo.setPeak(row.getRecord().getFaultSize());
            realtimeVo.setAlarm(row.getRecord().getFaultSize() != null && row.getRecord().getFaultSize() >= 5.0d);
            realtimeVo.setAlarmMessage(Boolean.TRUE.equals(realtimeVo.getAlarm()) ? "加速度超过报警阈值" : "");
            webSocketPushService.pushFeature(realtimeVo);
        }

        Db.saveBatch(rawEntities);
        VibrationCsvRow last = rows.get(rows.size() - 1);
        tdengineWriter.writeRawWave(dto.getDeviceCode(), last.getChannelId(), SensorSampleDtoAdapter.sample(dto.getDeviceCode(), rows, dto.getCollectTime()));
        tdengineWriter.writeFftPoints(dto.getDeviceCode(), last.getChannelId(), SensorSampleDtoAdapter.amplitudes(rows));
        tdengineWriter.writeCsvRecord(dto.getDeviceCode(), last.getChannelId(), last.getRecord(), last.getSampleTime());
        storageService.asyncSave(
            SensorSampleDtoAdapter.sample(dto.getDeviceCode(), rows, dto.getCollectTime()),
            SensorSampleDtoAdapter.feature(dto.getDeviceCode(), last),
            SensorSampleDtoAdapter.amplitudes(rows),
            last.getRecord().getFaultSize() != null && last.getRecord().getFaultSize() >= 5.0d,
            last.getChannelId()
        );
    }

    private List<VibrationCsvRow> parseBatch(ChannelFrameDTO dto)
    {
        List<VibrationCsvRow> result = new ArrayList<>();
        if (dto == null || dto.getPayload() == null || dto.getPayload().length == 0)
        {
            return result;
        }

        String text = new String(dto.getPayload(), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (text.isEmpty() || "KEEPALIVE".equalsIgnoreCase(text))
        {
            return result;
        }

        String[] lines = text.split("\\r?\\n");
        int limit = Math.min(lines.length, MAX_BATCH_LINES);
        for (int i = 0; i < limit; i++)
        {
            String line = lines[i].trim();
            if (line.isEmpty())
            {
                continue;
            }
            VibrationCsvProtocol protocol = VibrationCsvProtocol.parse(line);
            VibrationCsvRecord record = new VibrationCsvRecord(protocol);
            result.add(new VibrationCsvRow(dto.getCollectTime() == null ? new Date() : dto.getCollectTime(), dto.getBatchId() == null ? 1 : dto.getBatchId().intValue(), record));
        }
        return result;
    }
}
