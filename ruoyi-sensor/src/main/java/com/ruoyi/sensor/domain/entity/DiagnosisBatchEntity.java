package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sensor_diagnosis_batch")
public class DiagnosisBatchEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String clientRequestId;
    private String requestHash;
    private String deviceCode;
    private String modelType;
    private String modelVersion;
    private String status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private String createdBy;
    private Date createTime;
    private Date startTime;
    private Date finishTime;
    private Date updateTime;
}
