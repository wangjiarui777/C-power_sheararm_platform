package com.ruoyi.sensor.websocket;

import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.vo.SensorWebSocketMessageVo;
import com.ruoyi.system.domain.vo.MonitoringOverviewVo;
import com.ruoyi.system.service.IMonitoringService;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/ws/sensor")
public class SensorWebSocketHandler
{
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();

    /** Per-session subscription channels (e.g. "overview"). */
    private static final ConcurrentHashMap<Session, Set<String>> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session)
    {
        SESSIONS.add(session);
        SUBSCRIPTIONS.put(session, ConcurrentHashMap.newKeySet());
    }

    @OnMessage
    public void onMessage(String message, Session session)
    {
        try
        {
            JSONObject json = JSON.parseObject(message);
            String type = json.getString("type");
            if ("subscribe".equals(type))
            {
                String channel = json.getString("channel");
                if (channel != null && !channel.isEmpty())
                {
                    Set<String> subs = SUBSCRIPTIONS.get(session);
                    if (subs != null)
                    {
                        subs.add(channel);
                    }
                    // Send full overview snapshot immediately on subscribe
                    if ("overview".equals(channel))
                    {
                        try
                        {
                            IMonitoringService monitoringService = SpringUtils.getBean(IMonitoringService.class);
                            MonitoringOverviewVo overview = monitoringService.getOverview();
                            broadcastFullOverviewToSession(session, overview);
                        }
                        catch (Exception ignored)
                        {
                            // Bean not available in this context — client will get incremental updates
                        }
                    }
                }
            }
            else if ("unsubscribe".equals(type))
            {
                String channel = json.getString("channel");
                if (channel != null)
                {
                    Set<String> subs = SUBSCRIPTIONS.get(session);
                    if (subs != null)
                    {
                        subs.remove(channel);
                    }
                }
            }
            else if ("ping".equals(type))
            {
                SensorWebSocketMessageVo pong = new SensorWebSocketMessageVo();
                pong.setType("heartbeat");
                pong.setEvent("pong");
                sendToSession(session, pong);
            }
        }
        catch (Exception ignored)
        {
            // Ignore malformed messages
        }
    }

    @OnClose
    public void onClose(Session session)
    {
        SESSIONS.remove(session);
        SUBSCRIPTIONS.remove(session);
    }

    // ======================== existing broadcasts ========================

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

    // ======================== overview push (new) ========================

    /**
     * Push a full {@code MonitoringOverviewVo} snapshot to every client that is
     * subscribed to the "overview" channel.
     */
    public static void broadcastFullOverview(MonitoringOverviewVo overview)
    {
        if (overview == null)
        {
            return;
        }
        String payload = buildOverviewFullPayload(overview);
        for (Session session : SUBSCRIPTIONS.keySet())
        {
            if (session.isOpen() && isSubscribed(session, "overview"))
            {
                session.getAsyncRemote().sendText(payload);
            }
        }
    }

    /**
     * Push an incremental update when a single vibration or temperature data point
     * is uploaded.  Only clients subscribed to "overview" receive it.
     */
    public static void broadcastIncrementalUpdate(String deviceCode, String dataType,
                                                  Double value, Date sampleTime)
    {
        SensorWebSocketMessageVo msg = new SensorWebSocketMessageVo();
        msg.setType("overview");
        msg.setEvent("vibration".equals(dataType) ? "new_vibration" : "new_temperature");
        msg.setDeviceCode(deviceCode);
        if ("vibration".equals(dataType))
        {
            msg.setRms(value);
        }
        else
        {
            msg.setMessage(value != null ? value.toString() : null);
        }
        if (sampleTime != null)
        {
            msg.setSampleTime(new java.sql.Timestamp(sampleTime.getTime()).toLocalDateTime());
        }
        sendToSubscribed("overview", msg);
    }

    // ======================== private helpers ========================

    /** Send full overview to a single session (initial snapshot on subscribe). */
    private static void broadcastFullOverviewToSession(Session session, MonitoringOverviewVo overview)
    {
        if (overview == null || session == null || !session.isOpen())
        {
            return;
        }
        session.getAsyncRemote().sendText(buildOverviewFullPayload(overview));
    }

    private static String buildOverviewFullPayload(MonitoringOverviewVo overview)
    {
        SensorWebSocketMessageVo msg = new SensorWebSocketMessageVo();
        msg.setType("overview");
        msg.setEvent("full");
        msg.setMessage(JSON.toJSONString(overview));
        return JSON.toJSONString(msg);
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

    private static void sendToSession(Session session, SensorWebSocketMessageVo message)
    {
        if (session != null && session.isOpen())
        {
            session.getAsyncRemote().sendText(JSON.toJSONString(message));
        }
    }

    private static void sendToSubscribed(String channel, SensorWebSocketMessageVo message)
    {
        String payload = JSON.toJSONString(message);
        for (Session session : SUBSCRIPTIONS.keySet())
        {
            if (session.isOpen() && isSubscribed(session, channel))
            {
                session.getAsyncRemote().sendText(payload);
            }
        }
    }

    private static boolean isSubscribed(Session session, String channel)
    {
        Set<String> subs = SUBSCRIPTIONS.getOrDefault(session, Collections.emptySet());
        return subs.contains(channel);
    }
}
