package com.ruoyi.sensor.service;

import java.util.List;

import com.ruoyi.sensor.domain.vo.VibrationAnalysisResultVo;

public interface VibrationAnalysisService
{
    VibrationAnalysisResultVo analyze(double[] signal, double sampleRate, String deviceCode, Long batchId);

    List<VibrationAnalysisResultVo> analyzeBatch(List<double[]> batchSignals, double sampleRate);
}
