package com.ruoyi.sensor.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sensor_model_release")
public class ModelReleaseEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String modelName;
    private String modelType;
    private String semanticVersion;
    private String fileSha256;
    private String trainingDataVersion;
    private String validationDataVersion;
    private String thresholdVersion;
    private BigDecimal precisionScore;
    private BigDecimal recallScore;
    private BigDecimal severeRecallScore;
    private BigDecimal falsePositivePerDeviceDay;
    private BigDecimal confidenceThreshold;
    private Integer consecutiveHits;
    private Integer shadowDays;
    private Date shadowStartTime;
    private Date shadowEndTime;
    private String shadowResultStatus;
    private Integer cooldownMinutes;
    private String status;
    private String artifactUri;
    private String createdBy;
    private String activatedBy;
    private Date createTime;
    private Date activateTime;
    private Date updateTime;
    private String remark;
}
