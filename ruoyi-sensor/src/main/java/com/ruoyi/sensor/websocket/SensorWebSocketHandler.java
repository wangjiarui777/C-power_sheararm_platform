package com.ruoyi.sensor.websocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.vo.SensorWebSocketMessageVo;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/ws/sensor")
public class SensorWebSocketHandler
{
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();

    @OnOpen
    public void onOpen(Session session)
    {
        SESSIONS.add(session);
    }

    @OnMessage
    public void onMessage(String message, Session session)
    {
        // 预留：可接收前端订阅通道、数据类型或心跳消息
    }

    @OnClose
    public void onClose(Session session)
    {
        SESSIONS.remove(session);
    }

    public static void broadcast(ChannelRealtimeVo featureVo)
    {
        SensorWebSocketMessageVo message = new SensorWebSocketMessageVo();
        message.setType("realtime");
        message.setEvent("feature");
        message.setDeviceCode(featureVo.getDeviceCode());
        message.setChannelId(featureVo.getChannelId());
        message.setSampleTime(featureVo.getSampleTime());
        message.setRms(featureVo.getRms());
        message.setPeak(featureVo.getPeak());
        message.setStatus(Boolean.TRUE.equals(featureVo.getAlarm()) ? "failed" : "done");
        message.setResultState(Boolean.TRUE.equals(featureVo.getAlarm()) ? "failed" : "done");
        message.setDiagnosisDetail(featureVo.getAlarmMessage());
        message.setMessage(featureVo.getAlarmMessage());
        send(message);
    }

    public static void broadcastDiagnosis(SensorWebSocketMessageVo message)
    {
        if (message == null)
        {
            return;
        }
        if (message.getType() == null)
        {
            message.setType("diagnosis");
        }
        send(message);
    }

    private static void send(SensorWebSocketMessageVo message)
    {
        String payload = JSON.toJSONString(message);
        for (Session session : SESSIONS)
        {
            if (session.isOpen())
            {
                session.getAsyncRemote().sendText(payload);
            }
        }
    }
}
