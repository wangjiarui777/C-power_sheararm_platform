package com.ruoyi.sensor.domain.query;

import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PhmAcquisitionChannelQuery extends BaseEntity
{
    private Long deviceId;
    private Long pointId;
    private Long scopeUserId;
}
