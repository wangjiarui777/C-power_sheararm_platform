package com.ruoyi.sensor.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_alarm_rule")
public class PhmAlarmRuleEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String ruleName;
    private Long deviceId;
    private Long pointId;
    private String featureCode;
    private String alarmType;
    private BigDecimal highLimit;
    private BigDecimal highHighLimit;
    private Integer growthPeriod;
    private BigDecimal growthHighLimit;
    private BigDecimal growthHighHighLimit;
    private Integer consecutiveCount;
    private Integer deviceAlarmLevel;
    private String description;
    private String actionAdvice;
    private Boolean enabled;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
