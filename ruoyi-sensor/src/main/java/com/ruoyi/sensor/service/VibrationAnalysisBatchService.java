package com.ruoyi.sensor.service;

import java.util.List;

import com.ruoyi.sensor.domain.entity.VibrationAnalysisBatchEntity;

public interface VibrationAnalysisBatchService
{
    VibrationAnalysisBatchEntity getById(Long batchId);

    List<VibrationAnalysisBatchEntity> list(VibrationAnalysisBatchEntity query);

    int insert(VibrationAnalysisBatchEntity entity);

    int update(VibrationAnalysisBatchEntity entity);

    int deleteByIds(Long[] batchIds);
}
