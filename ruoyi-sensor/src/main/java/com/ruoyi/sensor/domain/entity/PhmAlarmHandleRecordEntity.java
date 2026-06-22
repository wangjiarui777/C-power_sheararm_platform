package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_alarm_handle_record")
public class PhmAlarmHandleRecordEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long alarmId;
    private String actionType;
    private String operatorName;
    private String ignoreReason;
    private String beforeStatus;
    private String afterStatus;
    private String assignee;
    private String remark;
    private Date createTime;
}
