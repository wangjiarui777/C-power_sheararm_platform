package com.ruoyi.sensor.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ruoyi.sensor.event.DataUploadEvent;
import com.ruoyi.sensor.service.TelemetryPipelineService;

/**
 * Listens for {@code DataUploadEvent} (published by the sensor module after each
 * vibration/temperature upload) and forwards the data to the WebSocket push layer.
 */
@Component
public class DataUploadEventListener
{
    @Autowired
    private TelemetryPipelineService telemetryPipelineService;

    @EventListener
    public void onDataUpload(DataUploadEvent event)
    {
        telemetryPipelineService.accept(TelemetryPipelineService.fromUpload(
                event.getDeviceCode(),
                event.getDataType(),
                event.getChannelId(),
                event.getValue(),
                event.getSampleTime()));
    }
}
