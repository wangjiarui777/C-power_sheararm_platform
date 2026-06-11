package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("phm_feature_config")
public class PhmFeatureConfigEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String featureCode;
    private String featureName;
    private String unit;
    private String signalType;
    private Integer displayOrder;
    private Boolean enabled;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
