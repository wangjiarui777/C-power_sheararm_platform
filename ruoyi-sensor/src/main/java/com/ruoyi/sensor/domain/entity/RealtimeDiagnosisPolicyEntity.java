package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_realtime_diagnosis_policy")
public class RealtimeDiagnosisPolicyEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deviceId;
    private Long pointId;
    private String modelType;
    private String modelVersion;
    private Integer windowSamples;
    private Integer strideSamples;
    private Integer minIntervalSeconds;
    private Integer alarmCooldownSeconds;
    private Boolean enabled;
    private String remark;
    private String createBy;
    private Date createTime;
    private Date updateTime;
}
