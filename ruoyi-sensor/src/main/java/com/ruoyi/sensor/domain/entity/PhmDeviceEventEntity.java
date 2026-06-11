package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_device_event")
public class PhmDeviceEventEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deviceId;
    private String deviceCode;
    private Date eventTime;
    private String eventType;
    private String eventContent;
    private String operatorName;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
