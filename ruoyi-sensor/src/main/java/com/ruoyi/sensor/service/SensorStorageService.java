package com.ruoyi.sensor.service;

import java.util.Date;
import java.util.List;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.ruoyi.sensor.domain.dto.SensorSampleDto;
import com.ruoyi.sensor.domain.entity.SensorAlarmEntity;
import com.ruoyi.sensor.domain.vo.SensorFeatureVo;
import com.ruoyi.sensor.tdengine.SensorTdengineWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步双写服务。
 */
@Service
public class SensorStorageService
{
    private static final Logger log = LoggerFactory.getLogger(SensorStorageService.class);
    private final SensorTdengineWriter tdengineWriter;

    public SensorStorageService(SensorTdengineWriter tdengineWriter)
    {
        this.tdengineWriter = tdengineWriter;
    }

    @Async
    public void asyncSave(SensorSampleDto sample, SensorFeatureVo featureVo, List<Double> freqAmplitude, boolean alarm, Integer channelId)
    {
        tdengineWriter.writeRawWave(sample.getDeviceCode(), channelId, sample);
        tdengineWriter.writeFftPoints(sample.getDeviceCode(), channelId, freqAmplitude);

        if (alarm)
        {
            SensorAlarmEntity alarmEntity = new SensorAlarmEntity();
            alarmEntity.setDeviceCode(featureVo.getDeviceCode());
            alarmEntity.setAlarmType("VIBRATION_TEMPERATURE");
            alarmEntity.setAlarmMessage(featureVo.getAlarmMessage());
            alarmEntity.setSampleTime(Date.from(featureVo.getSampleTime().atZone(java.time.ZoneId.systemDefault()).toInstant()));
            alarmEntity.setCreateTime(new Date());
            Db.save(alarmEntity);
            log.info("[MySQL] 保存告警记录: {}", alarmEntity);
        }
    }
}
