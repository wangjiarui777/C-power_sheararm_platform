package com.ruoyi.sensor.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_measure_point")
public class PhmMeasurePointEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deviceId;
    private String deviceCode;
    private String pointCode;
    private String pointName;
    private Integer channelId;
    private String signalType;
    private String featureCodes;
    private BigDecimal cardX;
    private BigDecimal cardY;
    private BigDecimal pointX;
    private BigDecimal pointY;
    private Integer displayOrder;
    private Boolean enabled;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
