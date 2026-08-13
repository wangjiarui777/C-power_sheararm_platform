package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("diagnosis_iotdb_sync")
public class DiagnosisIotdbSyncEntity
{
    @TableId(type = IdType.INPUT)
    private Long recordId;
    private String syncStatus;
    private Integer attemptCount;
    private Date nextRetryTime;
    private String leaseOwner;
    private Date lockedUntil;
    private String lastError;
    private Date createTime;
    private Date updateTime;
    private Date syncedTime;
}
