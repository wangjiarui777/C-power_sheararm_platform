package com.ruoyi.sensor.domain.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * �񶯷������ʵ�塣
 */
@TableName("vibration_analysis_record")
public class VibrationAnalysisRecordEntity
{
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("batch_id")
    private Long batchId;

    @TableField("device_code")
    private String deviceCode;

    private Double rms;

    private Double peak;

    @TableField("crest_factor")
    private Double crestFactor;

    private Double kurtosis;

    @TableField("centroid_frequency")
    private Double centroidFrequency;

    @TableField("rms_frequency")
    private Double rmsFrequency;

    @TableField("diagnosis_result")
    private String diagnosisResult;

    @TableField("wave_json")
    private String waveJson;

    @TableField("spectrum_json")
    private String spectrumJson;

    @TableField("create_time")
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Double getRms() { return rms; }
    public void setRms(Double rms) { this.rms = rms; }
    public Double getPeak() { return peak; }
    public void setPeak(Double peak) { this.peak = peak; }
    public Double getCrestFactor() { return crestFactor; }
    public void setCrestFactor(Double crestFactor) { this.crestFactor = crestFactor; }
    public Double getKurtosis() { return kurtosis; }
    public void setKurtosis(Double kurtosis) { this.kurtosis = kurtosis; }
    public Double getCentroidFrequency() { return centroidFrequency; }
    public void setCentroidFrequency(Double centroidFrequency) { this.centroidFrequency = centroidFrequency; }
    public Double getRmsFrequency() { return rmsFrequency; }
    public void setRmsFrequency(Double rmsFrequency) { this.rmsFrequency = rmsFrequency; }
    public String getDiagnosisResult() { return diagnosisResult; }
    public void setDiagnosisResult(String diagnosisResult) { this.diagnosisResult = diagnosisResult; }
    public String getWaveJson() { return waveJson; }
    public void setWaveJson(String waveJson) { this.waveJson = waveJson; }
    public String getSpectrumJson() { return spectrumJson; }
    public void setSpectrumJson(String spectrumJson) { this.spectrumJson = spectrumJson; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
