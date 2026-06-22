package com.ruoyi.sensor.domain.dto;

import lombok.Data;

@Data
public class PhmAlarmActionRequest
{
    private String ignoreReason;
    private String remark;
    private String assignee;
    private String resolution;
    private Boolean force;
}
