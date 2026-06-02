package com.ruoyi.sensor.service.impl;

import java.time.LocalDateTime;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;

public class SensorFeaturePushService
{
    public ChannelRealtimeVo buildRealtimeVo(String deviceCode,
                                             Integer channelId,
                                             LocalDateTime sampleTime,
                                             Double vibrationValue,
                                             Double accelerationValue,
                                             Double temperatureValue,
                                             Double maValue,
                                             Double rocValue,
                                             boolean alarm,
                                             String alarmMessage)
    {
        ChannelRealtimeVo vo = new ChannelRealtimeVo();
        vo.setDeviceCode(deviceCode);
        vo.setChannelId(channelId);
        vo.setSampleTime(sampleTime);
        vo.setVibrationValue(vibrationValue);
        vo.setAccelerationValue(accelerationValue);
        vo.setTemperatureValue(temperatureValue);
        vo.setMaValue(maValue);
        vo.setRocValue(rocValue);
        vo.setRms(vibrationValue);
        vo.setPeak(vibrationValue == null ? null : vibrationValue * 1.25d);
        vo.setAlarm(alarm);
        vo.setAlarmMessage(alarmMessage);
        return vo;
    }
}
