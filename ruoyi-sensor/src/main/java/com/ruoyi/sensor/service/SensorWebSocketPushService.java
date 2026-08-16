package com.ruoyi.sensor.service;

import java.time.LocalDateTime;
import java.util.Map;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.vo.SensorWebSocketMessageVo;
import com.ruoyi.sensor.websocket.SensorWebSocketHandler;
import org.springframework.stereotype.Service;

@Service
public class SensorWebSocketPushService
{
    public void pushFeature(ChannelRealtimeVo featureVo)
    {
        SensorWebSocketHandler.broadcast(featureVo);
    }

    public void pushDiagnosis(Map<String, Object> diagnosis)
    {
        if (diagnosis == null) return;
        SensorWebSocketMessageVo message = new SensorWebSocketMessageVo();
        message.setType("analysis");
        message.setEvent("analysis");
        message.setDeviceCode(stringValue(diagnosis.get("deviceCode"), ""));
        message.setPointId(number(diagnosis.get("pointId")));
        message.setChannelId(intValue(diagnosis.get("channelId"), 1));
        message.setStatus(stringValue(diagnosis.get("status"), "完成"));
        message.setResultState(stringValue(diagnosis.get("resultState"), "completed"));
        message.setDiagnosisResult(stringValue(diagnosis.get("diagnosisResult"), stringValue(diagnosis.get("diagnosisName"), "")));
        message.setDiagnosisName(stringValue(diagnosis.get("diagnosisName"), message.getDiagnosisResult()));
        message.setDiagnosisDetail(stringValue(diagnosis.get("diagnosisDetail"), ""));
        message.setModelType(stringValue(diagnosis.get("modelType"), ""));
        message.setModelVersion(stringValue(diagnosis.get("modelVersion"), ""));
        message.setSourceType(stringValue(diagnosis.get("sourceType"), "MANUAL"));
        message.setWindowId(stringValue(diagnosis.get("windowId"), null));
        message.setMessage(message.getDiagnosisDetail());
        message.setReceiveTime(LocalDateTime.now());
        SensorWebSocketHandler.broadcastDiagnosis(message);
    }

    private String stringValue(Object value, String fallback)
    {
        return value == null ? fallback : String.valueOf(value);
    }

    private Long number(Object value)
    {
        return value instanceof Number ? ((Number) value).longValue() : value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private Integer intValue(Object value, int fallback)
    {
        return value instanceof Number ? ((Number) value).intValue() : value == null ? fallback : Integer.valueOf(String.valueOf(value));
    }
}
