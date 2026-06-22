package com.ruoyi.sensor.websocket;

import java.util.Collections;
import java.util.Date;
import java.sql.Timestamp;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.domain.entity.PhmAlarmEventEntity;
import com.ruoyi.sensor.domain.vo.SensorWebSocketMessageVo;
import com.ruoyi.sensor.domain.vo.MonitoringOverviewVo;
import com.ruoyi.sensor.service.IMonitoringService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SensorWebSocketHandler extends TextWebSocketHandler
{
    private static final Set<WebSocketSession> SESSIONS = ConcurrentHashMap.newKeySet();

    /** Per-session subscription channels (e.g. "overview"). */
    private static final ConcurrentHashMap<WebSocketSession, Set<String>> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session)
    {
        SESSIONS.add(session);
        SUBSCRIPTIONS.put(session, ConcurrentHashMap.newKeySet());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage)
    {
        try
        {
            String message = textMessage.getPayload();
            JSONObject json = JSON.parseObject(message);
            String type = json.getString("type");
            if ("subscribe".equals(type))
            {
                String channel = json.getString("channel");
                if (channel != null && !channel.isEmpty() && canSubscribe(session, channel))
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

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
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
        message.setVibrationValue(featureVo.getVibrationValue());
        message.setTemperatureValue(featureVo.getTemperatureValue());
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

    public static void broadcastPhmAlarm(PhmAlarmEventEntity alarm)
    {
        broadcastPhmAlarm(alarm, "created");
    }

    public static void broadcastPhmAlarmChanged(PhmAlarmEventEntity alarm)
    {
        broadcastPhmAlarm(alarm, "changed");
    }

    public static void broadcastTelemetry(TelemetryEnvelope envelope)
    {
        if (envelope == null)
        {
            return;
        }
        SensorWebSocketMessageVo msg = new SensorWebSocketMessageVo();
        msg.setType("monitoring");
        msg.setEvent("metric.changed");
        msg.setDeviceCode(envelope.getDeviceCode());
        msg.setPointId(envelope.getPointId());
        msg.setChannelId(envelope.getChannelId());
        msg.setMetricCode(envelope.getMetricCode());
        msg.setQuality(envelope.getQuality());
        if ("temperature".equals(envelope.getMetricCode()))
        {
            msg.setTemperatureValue(envelope.getValue());
        }
        else
        {
            msg.setVibrationValue(envelope.getValue());
            msg.setRms(envelope.getValue());
        }
        if (envelope.getSampleTime() != null)
        {
            msg.setSampleTime(new Timestamp(envelope.getSampleTime().getTime()).toLocalDateTime());
        }
        if (envelope.getReceiveTime() != null)
        {
            msg.setReceiveTime(new Timestamp(envelope.getReceiveTime().getTime()).toLocalDateTime());
        }
        msg.setMessage(JSON.toJSONString(envelope));
        sendMonitoring(envelope.getDeviceCode(), envelope.getPointId(), msg);
    }

    private static void broadcastPhmAlarm(PhmAlarmEventEntity alarm, String event)
    {
        if (alarm == null)
        {
            return;
        }
        SensorWebSocketMessageVo msg = new SensorWebSocketMessageVo();
        msg.setType("phm_alarm");
        msg.setEvent(event);
        msg.setDeviceCode(alarm.getDeviceCode());
        msg.setDiagnosisResult(alarm.getDiagnosisResult());
        msg.setDiagnosisDetail(alarm.getRemark());
        msg.setRiskLevel(alarm.getAlarmLevel() == null ? null : String.valueOf(alarm.getAlarmLevel()));
        msg.setMessage(JSON.toJSONString(alarm));
        if (alarm.getAlarmTime() != null)
        {
            msg.setSampleTime(new java.sql.Timestamp(alarm.getAlarmTime().getTime()).toLocalDateTime());
        }
        sendToSubscribed("phm_alarm", msg);
        sendToSubscribed("overview", msg);
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
        for (WebSocketSession session : SUBSCRIPTIONS.keySet())
        {
            if (session.isOpen() && isSubscribed(session, "overview"))
            {
                sendText(session, payload);
            }
        }
    }

    /**
     * Push an incremental update when a single vibration or temperature data point
     * is uploaded.  Only clients subscribed to "overview" receive it.
     */
    public static void broadcastIncrementalUpdate(String deviceCode, String dataType, Integer channelId,
                                                  Double value, Date sampleTime)
    {
        SensorWebSocketMessageVo msg = new SensorWebSocketMessageVo();
        msg.setType("overview");
        msg.setEvent("vibration".equals(dataType) ? "new_vibration" : "new_temperature");
        msg.setDeviceCode(deviceCode);
        msg.setChannelId(channelId);
        if ("vibration".equals(dataType))
        {
            msg.setVibrationValue(value);
            msg.setRms(value);
        }
        else
        {
            msg.setTemperatureValue(value);
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
    private static void broadcastFullOverviewToSession(WebSocketSession session, MonitoringOverviewVo overview)
    {
        if (overview == null || session == null || !session.isOpen())
        {
            return;
        }
        sendText(session, buildOverviewFullPayload(overview));
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
        for (WebSocketSession session : SESSIONS)
        {
            if (session.isOpen() && canReceiveMessage(session, message))
            {
                sendText(session, payload);
            }
        }
    }

    private static void sendToSession(WebSocketSession session, SensorWebSocketMessageVo message)
    {
        if (session != null && session.isOpen())
        {
            sendText(session, JSON.toJSONString(message));
        }
    }

    private static void sendToSubscribed(String channel, SensorWebSocketMessageVo message)
    {
        String payload = JSON.toJSONString(message);
        for (WebSocketSession session : SUBSCRIPTIONS.keySet())
        {
            if (session.isOpen() && isSubscribed(session, channel))
            {
                sendText(session, payload);
            }
        }
    }

    private static void sendMonitoring(String deviceCode, Long pointId, SensorWebSocketMessageVo message)
    {
        String payload = JSON.toJSONString(message);
        for (WebSocketSession session : SUBSCRIPTIONS.keySet())
        {
            if (!session.isOpen())
            {
                continue;
            }
            boolean subscribed = isSubscribed(session, "monitoring")
                    || isSubscribed(session, "overview")
                    || isSubscribed(session, "device:" + deviceCode)
                    || (pointId != null && isSubscribed(session, "point:" + pointId));
            if (subscribed)
            {
                sendText(session, payload);
            }
        }
    }

    private static boolean isSubscribed(WebSocketSession session, String channel)
    {
        Set<String> subs = SUBSCRIPTIONS.getOrDefault(session, Collections.emptySet());
        return subs.contains(channel);
    }

    private static boolean canSubscribe(WebSocketSession session, String channel)
    {
        if ("phm_alarm".equals(channel))
        {
            return hasAnyPermission(session, "phm:alarm:list", "phm:alarm:query", "phm:alarm:handle");
        }
        if ("monitoring".equals(channel) || "overview".equals(channel)
                || channel.startsWith("device:") || channel.startsWith("point:"))
        {
            return hasAnyPermission(session, "sensor:monitoring:view", "sensor:vibration:list",
                    "sensor:temperature:list");
        }
        return false;
    }

    private static boolean canReceiveMessage(WebSocketSession session, SensorWebSocketMessageVo message)
    {
        String type = message == null ? null : message.getType();
        if ("diagnosis".equals(type) || "analysis".equals(type))
        {
            return hasAnyPermission(session, "sensor:diagnosis:view", "sensor:diagnosis:run");
        }
        return hasAnyPermission(session, "sensor:monitoring:view", "sensor:vibration:list",
                "sensor:temperature:list");
    }

    @SuppressWarnings("unchecked")
    private static boolean hasAnyPermission(WebSocketSession session, String... required)
    {
        Object value = session.getAttributes().get(SensorWebSocketHandshakeInterceptor.ATTR_PERMISSIONS);
        if (!(value instanceof Set<?>))
        {
            return false;
        }
        Set<String> permissions = (Set<String>) value;
        if (permissions.contains("*:*:*"))
        {
            return true;
        }
        for (String permission : required)
        {
            if (permissions.contains(permission))
            {
                return true;
            }
        }
        return false;
    }

    private static void sendText(WebSocketSession session, String payload)
    {
        try
        {
            synchronized (session)
            {
                if (session.isOpen())
                {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        }
        catch (Exception ignored)
        {
            SESSIONS.remove(session);
            SUBSCRIPTIONS.remove(session);
        }
    }
}
