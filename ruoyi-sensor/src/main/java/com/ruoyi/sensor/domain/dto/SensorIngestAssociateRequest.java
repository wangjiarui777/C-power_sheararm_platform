package com.ruoyi.sensor.domain.dto;

import lombok.Data;

@Data
public class SensorIngestAssociateRequest
{
    private Long deviceId;
    private Long pointId;
    /** Physical MAT acquisition channel number. */
    private Integer channelId;
}
