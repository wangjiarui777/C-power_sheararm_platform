package com.ruoyi.sensor.service;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.sensor.websocket.SensorWebSocketHandler;
import com.ruoyi.sensor.domain.vo.MonitoringOverviewVo;
import com.ruoyi.sensor.service.IMonitoringService;

/**
 * Bridges Spring-managed {@code IMonitoringService} with the non-Spring JSR 356
 * {@code SensorWebSocketHandler} so that monitoring overview data can be pushed
 * to WebSocket clients in real time whenever new vibration or temperature data
 * is uploaded.
 */
@Service
public class OverviewPushService
{
    @Autowired
    private IMonitoringService monitoringService;

    /**
     * Push an incremental update when a single data point is uploaded.
     *
     * @param deviceCode the device that generated this data point
     * @param dataType   "vibration" or "temperature"
     * @param value      the measured value
     * @param sampleTime the sample / collection timestamp
     */
    public void pushDataUpdate(String deviceCode, String dataType, Double value, Date sampleTime)
    {
        SensorWebSocketHandler.broadcastIncrementalUpdate(deviceCode, dataType, value, sampleTime);
    }

    /**
     * Push a full {@code MonitoringOverviewVo} snapshot to all clients subscribed
     * to the "overview" channel.  Typically called after a client sends a
     * {@code subscribe} message so it receives initial state immediately.
     */
    public void pushFullOverview()
    {
        MonitoringOverviewVo overview = monitoringService.getOverview();
        SensorWebSocketHandler.broadcastFullOverview(overview);
    }
}
