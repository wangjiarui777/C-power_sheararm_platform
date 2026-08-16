package com.ruoyi.sensor.domain.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

@Data
@TableName("sensor_ingest_file")
public class SensorIngestFileEntity
{
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceType;
    private String sourceRef;
    private Long attachmentId;
    private Long deviceId;
    private String deviceCode;
    private Long pointId;
    private String pointCode;
    private Integer channelId;
    private String fileName;
    private String fileExt;
    private Long fileSize;
    private String sha256;
    private String status;
    private String errorCode;
    private String errorMessage;
    private Integer retryCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date receivedTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validatedTime;
    private String createBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
