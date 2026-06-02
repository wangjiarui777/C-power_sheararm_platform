package com.ruoyi.sensor.service;

import java.time.LocalDateTime;

import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.vo.MultiChannelAnalysisVo;
import com.ruoyi.sensor.websocket.SensorWebSocketHandler;
import org.springframework.stereotype.Service;

@Service
public class ChannelRealtimePushService
{
    public void push(String deviceCode, Double vibrationValue, Double temperatureValue, MultiChannelAnalysisVo analysisVo)
    {
        if (analysisVo == null)
        {
            return;
        }

        ChannelRealtimeVo vo = new ChannelRealtimeVo();
        vo.setDeviceCode(deviceCode);
        vo.setChannelId(analysisVo.getChannelId());
        vo.setSampleTime(LocalDateTime.now());
        vo.setVibrationValue(vibrationValue);
        vo.setTemperatureValue(temperatureValue);
        vo.setRms(analysisVo.getRms());
        vo.setPeak(analysisVo.getPeak());
        vo.setAlarm(Boolean.FALSE);
        vo.setAlarmMessage(analysisVo.getDiagnosis());

        SensorWebSocketHandler.broadcast(vo);
    }
}
