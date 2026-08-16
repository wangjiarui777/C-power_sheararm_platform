package com.ruoyi.sensor.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_acquisition_channel")
public class PhmAcquisitionChannelEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String collectorId;
    private Integer moduleNo;
    private Integer channelNo;
    private Long deviceId;
    private String deviceCode;
    private Long pointId;
    private String pointCode;
    private String signalType;
    private BigDecimal sampleRate;
    private String unit;
    private BigDecimal scaleFactor;
    private BigDecimal offsetValue;
    private Integer qualityPolicySeconds;
    private String sensorModel;
    private String mountType;
    private String mountPosition;
    private BigDecimal sensitivity;
    private BigDecimal rangeValue;
    private Date calibrationDate;
    private Boolean enabled;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
