package com.ruoyi.sensor.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_alarm_event")
public class PhmAlarmEventEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String alarmNo;
    private Long deviceId;
    private String deviceCode;
    private String deviceName;
    private Long pointId;
    private String pointName;
    private String featureCode;
    private String alarmScope;
    private String alarmType;
    private String alarmSource;
    private Integer alarmLevel;
    private String pointAlarmLevel;
    private BigDecimal alarmValue;
    private String diagnosisResult;
    private String status;
    private String conditionStatus;
    private String workflowStatus;
    private String assignee;
    private String acknowledgedBy;
    private Date acknowledgedTime;
    private String closedBy;
    private Date closedTime;
    private String resolution;
    private Integer occurrenceCount;
    private Date firstTriggerTime;
    private Date lastTriggerTime;
    private String handler;
    private Date handleTime;
    private String ignoreReason;
    private String handleRemark;
    private Long relatedRecordId;
    private Date alarmTime;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
