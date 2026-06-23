package com.ruoyi.sensor.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("enhanced_inference_record")
public class EnhancedInferenceRecordEntity
{
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private String deviceCode;
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
    private String waveJson;
    private String spectrumJson;
    private String timeseriesRef;
    private Long modelReleaseId;
    private Date sampleTime;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
