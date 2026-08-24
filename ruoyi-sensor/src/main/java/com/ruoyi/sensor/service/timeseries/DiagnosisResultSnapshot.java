package com.ruoyi.sensor.service.timeseries;

import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/** Storage-neutral representation of one completed model diagnosis. */
@Data
public class DiagnosisResultSnapshot
{
    private Long recordId;
    private Long batchId;
    private Long taskId;
    private String sourceType;
    private String deviceCode;
    private Long pointId;
    private Integer channelId;
    private String modelVersion;
    private String sourceFile;
    private String analysisMode;
    private Double sampleRate;
    private String diagnosisResult;
    private String closedPrediction;
    private BigDecimal confidence;
    private Integer healthIndex;
    private String riskLevel;
    private String alarmLevel;
    private String diagnosisDetail;
    private String decisionReason;
    private BigDecimal unknownRatio;
    private BigDecimal segmentConsistency;
    private BigDecimal meanMahalanobis;
    private BigDecimal meanEntropy;
    private Double rms;
    private Double peak;
    private String topProbabilities;
    private String evidence;
    private String timeseriesRef;
    private Long modelReleaseId;
    private Date sampleTime;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
