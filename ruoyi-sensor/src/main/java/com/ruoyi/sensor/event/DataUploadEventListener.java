package com.ruoyi.sensor.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ruoyi.sensor.service.OverviewPushService;
import com.ruoyi.system.event.DataUploadEvent;

/**
 * Listens for {@code DataUploadEvent} (published by the system module after each
 * vibration/temperature upload) and forwards the data to the WebSocket push layer.
 */
@Component
public class DataUploadEventListener
{
    @Autowired
    private OverviewPushService overviewPushService;

    @EventListener
    public void onDataUpload(DataUploadEvent event)
    {
        overviewPushService.pushDataUpdate(
                event.getDeviceCode(),
                event.getDataType(),
                event.getValue(),
                event.getSampleTime());
    }
}
