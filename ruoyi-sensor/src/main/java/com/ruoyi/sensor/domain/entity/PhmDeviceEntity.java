package com.ruoyi.sensor.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_device")
public class PhmDeviceEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deptId;
    @TableField(exist = false)
    private String deptName;
    private String deviceCode;
    private String deviceName;
    private String deviceType;
    private String orgName;
    private String location;
    private String modelName;
    private String manufacturer;
    private String status;
    private Integer healthIndex;
    private String faultType;
    private BigDecimal runHours;
    private Date lastAlarmTime;
    private String nameplateJson;
    private String processJson;
    private String morphologyUrl;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
    private String remark;
}
