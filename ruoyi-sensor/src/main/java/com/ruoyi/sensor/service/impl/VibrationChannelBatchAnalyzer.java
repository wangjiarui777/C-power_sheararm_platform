package com.ruoyi.sensor.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ruoyi.sensor.domain.vo.MultiChannelAnalysisVo;
import com.ruoyi.sensor.domain.vo.VibrationAnalysisResultVo;
import org.springframework.stereotype.Component;

@Component
public class VibrationChannelBatchAnalyzer
{
    private final VibrationAnalysisServiceImpl vibrationAnalysisService;

    public VibrationChannelBatchAnalyzer(VibrationAnalysisServiceImpl vibrationAnalysisService)
    {
        this.vibrationAnalysisService = vibrationAnalysisService;
    }

    public List<MultiChannelAnalysisVo> analyzeBatch(String deviceCode, Long batchId,
            List<ChannelSignal> channelSignals, double sampleRate)
    {
        if (channelSignals == null || channelSignals.isEmpty())
        {
            return Collections.emptyList();
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, channelSignals.size()));
        try
        {
            List<CompletableFuture<MultiChannelAnalysisVo>> futures = new ArrayList<>();
            for (ChannelSignal channelSignal : channelSignals)
            {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    VibrationAnalysisResultVo result = vibrationAnalysisService.analyze(
                            channelSignal.getSignal(), sampleRate, deviceCode, batchId);
                    MultiChannelAnalysisVo vo = new MultiChannelAnalysisVo();
                    vo.setChannelId(channelSignal.getChannelId());
                    vo.setFrequencyAxis(result.getFrequencyAxis());
                    vo.setSpectrum(result.getSpectrum());
                    vo.setRms(result.getRms());
                    vo.setPeak(result.getPeak());
                    vo.setCrestFactor(result.getCrestFactor());
                    vo.setKurtosis(result.getKurtosis());
                    vo.setCentroidFrequency(result.getCentroidFrequency());
                    vo.setRmsFrequency(result.getRmsFrequency());
                    vo.setDiagnosis(result.getDiagnosis());
                    return vo;
                }, pool));
            }

            List<MultiChannelAnalysisVo> out = new ArrayList<>();
            for (CompletableFuture<MultiChannelAnalysisVo> future : futures)
            {
                out.add(future.join());
            }
            return out;
        }
        finally
        {
            pool.shutdown();
        }
    }

    public static class ChannelSignal
    {
        private Integer channelId;
        private double[] signal;

        public Integer getChannelId() { return channelId; }
        public void setChannelId(Integer channelId) { this.channelId = channelId; }
        public double[] getSignal() { return signal; }
        public void setSignal(double[] signal) { this.signal = signal; }
    }
}
