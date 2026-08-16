package com.ruoyi.sensor.domain.query;

import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SensorIngestFileQuery extends BaseEntity
{
    private Long id;
    private String status;
    private String sourceType;
    private String keyword;
}
