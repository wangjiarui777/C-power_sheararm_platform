package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sensor_inference_task")
public class InferenceTaskEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String requestId;
    private String idempotencyKey;
    private Long batchId;
    private Integer attemptNo;
    private Long supersedesTaskId;
    private String deviceCode;
    private Long pointId;
    private Integer channelId;
    private String modelType;
    private String requestedModelVersion;
    private String inputType;
    private String inputRef;
    private String inputSha256;
    private String sourceType;
    private Date queuedAt;
    private Integer attemptCount;
    private String status;
    private String errorCode;
    private String errorMessage;
    private String inputJson;
    private String resultJson;
    private String createdBy;
    private Date createTime;
    private Date startTime;
    private Date finishTime;
    private Date updateTime;
}
