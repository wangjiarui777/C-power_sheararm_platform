package com.ruoyi.sensor.service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.ruoyi.sensor.domain.dto.SensorSampleDto;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.domain.dto.VibrationCsvRecord;
import com.ruoyi.sensor.domain.dto.VibrationFrameEnvelope;
import com.ruoyi.sensor.domain.entity.SensorAlarmEntity;
import com.ruoyi.sensor.domain.vo.SensorFeatureVo;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步时序归档服务。
 */
@Service
public class SensorStorageService
{
    private static final Logger log = LoggerFactory.getLogger(SensorStorageService.class);
    private final TimeSeriesStore timeSeriesStore;

    public SensorStorageService(TimeSeriesStore timeSeriesStore)
    {
        this.timeSeriesStore = timeSeriesStore;
    }

    @Async
    public void asyncSave(SensorSampleDto sample, SensorFeatureVo featureVo, List<Double> freqAmplitude,
                          boolean alarm, Integer channelId, VibrationCsvRecord record)
    {
        writeFrame(sample, featureVo, freqAmplitude, channelId, record);
        writeTelemetry(sample, featureVo, channelId);

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

    private void writeFrame(SensorSampleDto sample, SensorFeatureVo featureVo, List<Double> freqAmplitude,
                            Integer channelId, VibrationCsvRecord record)
    {
        if (sample == null)
        {
            return;
        }
        VibrationFrameEnvelope envelope = new VibrationFrameEnvelope();
        envelope.setDeviceCode(sample.getDeviceCode());
        envelope.setChannelId(channelId);
        envelope.setSampleRate(sample.getSampleRate());
        envelope.setSampleCount(sample.getWaveform() == null ? 0 : sample.getWaveform().length);
        envelope.setWaveform(sample.getWaveform() == null ? java.util.Collections.emptyList()
                : java.util.Arrays.stream(sample.getWaveform()).boxed().collect(Collectors.toList()));
        envelope.setSpectrum(freqAmplitude);
        envelope.setFreqStep(freqStep(sample.getSampleRate(), freqAmplitude));
        envelope.setQuality("GOOD");
        envelope.setAxis("radial");
        envelope.setUnit("mm/s");
        envelope.setSampleTime(new Date(sample.getSampleTime()));
        envelope.setReceiveTime(new Date());
        if (featureVo != null)
        {
            envelope.setFaultSize(featureVo.getPeak());
        }
        if (record != null)
        {
            envelope.setRpm(record.getRpm());
            envelope.setLoad(record.getLoad());
            envelope.setFaultType(record.getFaultType());
            envelope.setFaultSize(record.getFaultSize());
        }
        timeSeriesStore.writeVibrationFrame(envelope);
    }

    private void writeTelemetry(SensorSampleDto sample, SensorFeatureVo featureVo, Integer channelId)
    {
        if (sample == null || featureVo == null || featureVo.getRms() == null)
        {
            return;
        }
        TelemetryEnvelope envelope = new TelemetryEnvelope();
        envelope.setDeviceCode(sample.getDeviceCode());
        envelope.setChannelId(channelId);
        envelope.setMetricCode("vibration");
        envelope.setSignalType("vibration");
        envelope.setSource("channel-frame-ingest");
        envelope.setUnit("mm/s");
        envelope.setValue(featureVo.getRms());
        envelope.setQuality(Boolean.TRUE.equals(featureVo.getAlarm()) ? "ALARM" : "GOOD");
        envelope.setSampleTime(new Date(sample.getSampleTime()));
        envelope.setReceiveTime(new Date());
        envelope.setSequence(sample.getSampleTime());
        envelope.normalize();
        timeSeriesStore.writeTelemetry(envelope);
    }

    private Double freqStep(int sampleRate, List<Double> spectrum)
    {
        if (sampleRate <= 0 || spectrum == null || spectrum.isEmpty())
        {
            return null;
        }
        return sampleRate / (2D * spectrum.size());
    }
}
