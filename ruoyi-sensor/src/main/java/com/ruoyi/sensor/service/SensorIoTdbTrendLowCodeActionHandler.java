package com.ruoyi.sensor.service;

import java.util.Map;
import com.ruoyi.common.lowcode.LowCodeActionContext;
import com.ruoyi.common.lowcode.LowCodeActionHandler;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;
import org.springframework.stereotype.Component;

/** Read-only IoTDB trend connector exposed as a registered low-code action. */
@Component
public class SensorIoTdbTrendLowCodeActionHandler implements LowCodeActionHandler
{
    private final TimeSeriesStore store;
    public SensorIoTdbTrendLowCodeActionHandler(TimeSeriesStore store) { this.store = store; }
    @Override public String code() { return "iotdb.telemetry.trend"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, LowCodeActionContext context)
    {
        String deviceCode = required(input.get("deviceCode"), "deviceCode");
        String pointCode = required(input.get("pointCode"), "pointCode");
        String metricCode = required(input.get("metricCode"), "metricCode");
        int limit = Math.max(1, Math.min(number(input.get("limit"), 200), 2000));
        return Map.of("deviceCode", deviceCode, "pointCode", pointCode, "metricCode", metricCode,
            "rows", store.queryTelemetryTrend(deviceCode, pointCode, metricCode, null, null, limit));
    }

    private String required(Object value, String name)
    {
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(name + " 为必填项");
        String text = String.valueOf(value).trim();
        if (text.length() > 128) throw new IllegalArgumentException(name + " 过长");
        return text;
    }
    private int number(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ex) { return fallback; } }
}
