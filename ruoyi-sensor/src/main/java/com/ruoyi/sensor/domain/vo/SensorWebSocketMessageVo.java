package com.ruoyi.sensor.domain.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class SensorWebSocketMessageVo
{
    private String type;
    private String event;
    private String deviceCode;
    private Long pointId;
    private Integer channelId;
    private String metricCode;
    private String quality;
    private String batchId;
    private String status;
    private String resultState;
    private String diagnosisResult;
    private String diagnosisName;
    private String diagnosisDetail;
    private String modelType;
    private String modelVersion;
    private String sourceType;
    private String windowId;
    private Long queueDelayMs;
    private Long endToEndLatencyMs;
    private Double confidence;
    private Double healthIndex;
    private String riskLevel;
    private Double vibrationValue;
    private Double temperatureValue;
    private Double rms;
    private Double peak;
    private LocalDateTime sampleTime;
    private LocalDateTime receiveTime;
    private List<Double> waveform;
    private List<Double> frequencyAxis;
    private List<Double> spectrum;
    private List<Map<String, Object>> evidence;
    private String message;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }
    public Integer getChannelId() { return channelId; }
    public void setChannelId(Integer channelId) { this.channelId = channelId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultState() { return resultState; }
    public void setResultState(String resultState) { this.resultState = resultState; }
    public String getDiagnosisResult() { return diagnosisResult; }
    public void setDiagnosisResult(String diagnosisResult) { this.diagnosisResult = diagnosisResult; }
    public String getDiagnosisName() { return diagnosisName; }
    public void setDiagnosisName(String diagnosisName) { this.diagnosisName = diagnosisName; }
    public String getDiagnosisDetail() { return diagnosisDetail; }
    public void setDiagnosisDetail(String diagnosisDetail) { this.diagnosisDetail = diagnosisDetail; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getWindowId() { return windowId; }
    public void setWindowId(String windowId) { this.windowId = windowId; }
    public Long getQueueDelayMs() { return queueDelayMs; }
    public void setQueueDelayMs(Long queueDelayMs) { this.queueDelayMs = queueDelayMs; }
    public Long getEndToEndLatencyMs() { return endToEndLatencyMs; }
    public void setEndToEndLatencyMs(Long endToEndLatencyMs) { this.endToEndLatencyMs = endToEndLatencyMs; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public Double getHealthIndex() { return healthIndex; }
    public void setHealthIndex(Double healthIndex) { this.healthIndex = healthIndex; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Double getVibrationValue() { return vibrationValue; }
    public void setVibrationValue(Double vibrationValue) { this.vibrationValue = vibrationValue; }
    public Double getTemperatureValue() { return temperatureValue; }
    public void setTemperatureValue(Double temperatureValue) { this.temperatureValue = temperatureValue; }
    public Double getRms() { return rms; }
    public void setRms(Double rms) { this.rms = rms; }
    public Double getPeak() { return peak; }
    public void setPeak(Double peak) { this.peak = peak; }
    public LocalDateTime getSampleTime() { return sampleTime; }
    public void setSampleTime(LocalDateTime sampleTime) { this.sampleTime = sampleTime; }
    public LocalDateTime getReceiveTime() { return receiveTime; }
    public void setReceiveTime(LocalDateTime receiveTime) { this.receiveTime = receiveTime; }
    public List<Double> getWaveform() { return waveform; }
    public void setWaveform(List<Double> waveform) { this.waveform = waveform; }
    public List<Double> getFrequencyAxis() { return frequencyAxis; }
    public void setFrequencyAxis(List<Double> frequencyAxis) { this.frequencyAxis = frequencyAxis; }
    public List<Double> getSpectrum() { return spectrum; }
    public void setSpectrum(List<Double> spectrum) { this.spectrum = spectrum; }
    public List<Map<String, Object>> getEvidence() { return evidence; }
    public void setEvidence(List<Map<String, Object>> evidence) { this.evidence = evidence; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
