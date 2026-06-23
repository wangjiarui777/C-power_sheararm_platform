package com.ruoyi.sensor.domain.dto;

import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TelemetryAcceptance
{
    private String eventId;
    private Date acceptedAt;
    private boolean duplicate;
    private String queueStatus;
}
