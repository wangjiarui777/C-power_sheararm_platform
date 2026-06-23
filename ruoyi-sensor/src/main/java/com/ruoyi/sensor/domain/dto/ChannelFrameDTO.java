package com.ruoyi.sensor.domain.dto;

import java.util.Date;

public class ChannelFrameDTO
{
    private String schemaVersion;
    private String frameId;
    private String collectorId;
    private String deviceCode;
    private Long sequence;
    private String quality;
    private Long batchId;
    private Double sampleRate;
    private Date collectTime;
    private byte[] payload;

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getFrameId() { return frameId; }
    public void setFrameId(String frameId) { this.frameId = frameId; }
    public String getCollectorId() { return collectorId; }
    public void setCollectorId(String collectorId) { this.collectorId = collectorId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Long getSequence() { return sequence; }
    public void setSequence(Long sequence) { this.sequence = sequence; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Double getSampleRate() { return sampleRate; }
    public void setSampleRate(Double sampleRate) { this.sampleRate = sampleRate; }
    public Date getCollectTime() { return collectTime; }
    public void setCollectTime(Date collectTime) { this.collectTime = collectTime; }
    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }
}
