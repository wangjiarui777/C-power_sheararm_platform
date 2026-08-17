package com.ruoyi.sensor.domain.query;

import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PhmDeviceScopeQuery extends BaseEntity
{
    private Long deviceId;
    private String deviceCode;
    private String keyword;
    private String orgName;
    private String status;
    /** Null means unrestricted access for the administrator. */
    private Long scopeUserId;
}
