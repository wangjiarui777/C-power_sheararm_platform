package com.ruoyi.sensor.domain.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * �񶯲ɼ�����ʵ�塣
 */
@TableName("vibration_batch")
public class VibrationBatchEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("batch_no")
    private String batchNo;

    @TableField("device_code")
    private String deviceCode;

    @TableField("sample_rate")
    private Integer sampleRate;

    @TableField("sample_count")
    private Integer sampleCount;

    @TableField("sample_time")
    private Date sampleTime;

    @TableField("create_time")
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Integer getSampleRate() { return sampleRate; }
    public void setSampleRate(Integer sampleRate) { this.sampleRate = sampleRate; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public Date getSampleTime() { return sampleTime; }
    public void setSampleTime(Date sampleTime) { this.sampleTime = sampleTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
