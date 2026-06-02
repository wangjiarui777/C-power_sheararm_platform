package com.ruoyi.sensor.service;

import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.websocket.SensorWebSocketHandler;
import org.springframework.stereotype.Service;

@Service
public class SensorWebSocketPushService
{
    public void pushFeature(ChannelRealtimeVo featureVo)
    {
        SensorWebSocketHandler.broadcast(featureVo);
    }
}
