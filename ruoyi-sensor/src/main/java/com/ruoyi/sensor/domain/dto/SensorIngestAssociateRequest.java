package com.ruoyi.sensor.domain.dto;

import lombok.Data;

@Data
public class SensorIngestAssociateRequest
{
    private Long deviceId;
    private Long pointId;
    /** phm_acquisition_channel primary key, not the physical channel number. */
    private Long channelId;
}
