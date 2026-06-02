package com.ruoyi.sensor.domain.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * �񶯷�����ʷ�ɼ�����ʵ�塣
 */
@TableName("vibration_analysis_batch")
public class VibrationAnalysisBatchEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long batchId;

    @TableField("device_code")
    private String deviceCode;

    @TableField("sample_rate")
    private Double sampleRate;

    @TableField("sample_count")
    private Integer sampleCount;

    @TableField("collect_time")
    private Date collectTime;

    @TableField("create_time")
    private Date createTime;

    private String remark;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Double getSampleRate() { return sampleRate; }
    public void setSampleRate(Double sampleRate) { this.sampleRate = sampleRate; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public Date getCollectTime() { return collectTime; }
    public void setCollectTime(Date collectTime) { this.collectTime = collectTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
