package com.ruoyi.sensor.service;

import java.util.List;

import com.ruoyi.sensor.domain.entity.VibrationAnalysisRecordEntity;

public interface VibrationAnalysisPersistenceService
{
    void saveAsync(VibrationAnalysisRecordEntity record);

    void saveBatchAsync(List<VibrationAnalysisRecordEntity> records);
}
