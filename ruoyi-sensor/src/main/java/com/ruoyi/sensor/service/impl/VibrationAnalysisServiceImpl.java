package com.ruoyi.sensor.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.sensor.domain.entity.VibrationAnalysisRecordEntity;
import com.ruoyi.sensor.domain.vo.VibrationAnalysisResultVo;
import com.ruoyi.sensor.service.VibrationAnalysisPersistenceService;
import com.ruoyi.sensor.service.VibrationAnalysisService;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jtransforms.fft.DoubleFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * �񶯷���ʵ�֣�ȥֱ�� + Hann �Ӵ� + FFT + ��� + �첽��⡣
 */
@Service
public class VibrationAnalysisServiceImpl implements VibrationAnalysisService
{
    private static final Logger log = LoggerFactory.getLogger(VibrationAnalysisServiceImpl.class);
    private static final Random RANDOM = new Random();

    private final VibrationAnalysisPersistenceService persistenceService;

    public VibrationAnalysisServiceImpl(VibrationAnalysisPersistenceService persistenceService)
    {
        this.persistenceService = persistenceService;
    }

    @Override
    public VibrationAnalysisResultVo analyze(double[] signal, double sampleRate, String deviceCode, Long batchId)
    {
        VibrationAnalysisResultVo vo = new VibrationAnalysisResultVo();
        if (signal == null || signal.length < 2)
        {
            vo.setDiagnosis("����Ч����");
            return vo;
        }

        double[] processed = preprocess(signal);
        double rms = calcRms(processed);
        double peak = calcPeak(processed);
        double crestFactor = rms == 0 ? 0 : peak / rms;
        double kurtosis = calcKurtosis(processed);

        double[][] spectrumData = calcSpectrum(processed, sampleRate);
        double[] freqAxis = spectrumData[0];
        double[] spectrum = spectrumData[1];
        double centroidFrequency = calcCentroidFrequency(freqAxis, spectrum);
        double rmsFrequency = calcRmsFrequency(freqAxis, spectrum);

        String diagnosis = judgeDiagnosis(kurtosis, crestFactor, freqAxis, spectrum);

        vo.setRms(rms);
        vo.setPeak(peak);
        vo.setCrestFactor(crestFactor);
        vo.setKurtosis(kurtosis);
        vo.setCentroidFrequency(centroidFrequency);
        vo.setRmsFrequency(rmsFrequency);
        vo.setFrequencyAxis(freqAxis);
        vo.setSpectrum(spectrum);
        vo.setDiagnosis(diagnosis);

        VibrationAnalysisRecordEntity record = new VibrationAnalysisRecordEntity();
        record.setBatchId(batchId == null ? 0L : batchId);
        record.setDeviceCode(deviceCode == null ? "UNKNOWN" : deviceCode);
        record.setRms(rms);
        record.setPeak(peak);
        record.setCrestFactor(crestFactor);
        record.setKurtosis(kurtosis);
        record.setCentroidFrequency(centroidFrequency);
        record.setRmsFrequency(rmsFrequency);
        record.setDiagnosisResult(diagnosis);
        record.setWaveJson(JSON.toJSONString(signal));
        record.setSpectrumJson(JSON.toJSONString(spectrum));
        record.setCreateTime(new Date());

        persistenceService.saveAsync(record);
        return vo;
    }

    @Override
    public List<VibrationAnalysisResultVo> analyzeBatch(List<double[]> batchSignals, double sampleRate)
    {
        List<VibrationAnalysisResultVo> result = new ArrayList<>();
        if (batchSignals == null)
        {
            return result;
        }
        for (double[] signal : batchSignals)
        {
            result.add(analyze(signal, sampleRate, null, null));
        }
        return result;
    }

    public static double[] generateMockWave(String mode, int sampleCount, double sampleRate)
    {
        double[] data = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++)
        {
            double t = i / sampleRate;
            double base = Math.sin(2 * Math.PI * 50 * t);
            double harmonic = 0D;
            double noise = 0.03 * (RANDOM.nextDouble() - 0.5);

            switch (mode == null ? "normal" : mode)
            {
                case "unbalance":
                    harmonic = 0.35 * Math.sin(2 * Math.PI * 50 * t);
                    break;
                case "misalignment":
                    harmonic = 0.25 * Math.sin(2 * Math.PI * 100 * t)
                            + 0.15 * Math.sin(2 * Math.PI * 150 * t);
                    break;
                case "bearing_wear":
                    harmonic = 0.12 * Math.sin(2 * Math.PI * 50 * t)
                            + 0.20 * Math.sin(2 * Math.PI * 300 * t)
                            + 0.10 * Math.sin(2 * Math.PI * 600 * t);
                    noise += 0.08 * (RANDOM.nextDouble() - 0.5);
                    break;
                default:
                    harmonic = 0.15 * Math.sin(2 * Math.PI * 100 * t);
                    break;
            }
            data[i] = base + harmonic + noise;
        }
        return data;
    }

    private double[] preprocess(double[] signal)
    {
        double mean = 0D;
        for (double v : signal)
        {
            mean += v;
        }
        mean /= signal.length;

        double[] centered = new double[signal.length];
        int n = signal.length;
        for (int i = 0; i < n; i++)
        {
            double window = 0.5 * (1 - Math.cos((2.0 * Math.PI * i) / (n - 1)));
            centered[i] = (signal[i] - mean) * window;
        }
        return centered;
    }

    private double calcRms(double[] values)
    {
        double sum = 0D;
        for (double v : values)
        {
            sum += v * v;
        }
        return Math.sqrt(sum / values.length);
    }

    private double calcPeak(double[] values)
    {
        double peak = 0D;
        for (double v : values)
        {
            peak = Math.max(peak, Math.abs(v));
        }
        return peak;
    }

    private double calcKurtosis(double[] signal)
    {
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (double v : signal)
        {
            stats.addValue(v);
        }
        return stats.getKurtosis() + 3.0;
    }

    private double[][] calcSpectrum(double[] signal, double sampleRate)
    {
        int n = signal.length;
        int fftSize = nextPow2(n);
        double[] data = new double[2 * fftSize];
        System.arraycopy(signal, 0, data, 0, n);

        DoubleFFT_1D fft = new DoubleFFT_1D(fftSize);
        fft.realForwardFull(data);

        int spectrumLen = fftSize / 2;
        double[] freqAxis = new double[spectrumLen];
        double[] spectrum = new double[spectrumLen];
        for (int i = 0; i < spectrumLen; i++)
        {
            double re = data[2 * i];
            double im = data[2 * i + 1];
            spectrum[i] = Math.sqrt(re * re + im * im) / fftSize;
            freqAxis[i] = i * sampleRate / fftSize;
        }
        return new double[][]{freqAxis, spectrum};
    }

    private double calcCentroidFrequency(double[] freqAxis, double[] spectrum)
    {
        double numerator = 0D;
        double denominator = 0D;
        for (int i = 0; i < freqAxis.length; i++)
        {
            numerator += freqAxis[i] * spectrum[i];
            denominator += spectrum[i];
        }
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private double calcRmsFrequency(double[] freqAxis, double[] spectrum)
    {
        double numerator = 0D;
        double denominator = 0D;
        for (int i = 0; i < freqAxis.length; i++)
        {
            numerator += freqAxis[i] * freqAxis[i] * spectrum[i];
            denominator += spectrum[i];
        }
        return denominator == 0 ? 0 : Math.sqrt(numerator / denominator);
    }

    private String judgeDiagnosis(double kurtosis, double crestFactor, double[] freqAxis, double[] spectrum)
    {
        int peakIndex = findPeakIndex(spectrum);
        double peakFreq = peakIndex >= 0 && peakIndex < freqAxis.length ? freqAxis[peakIndex] : 0D;

        if (kurtosis > 4.5 && crestFactor > 5.0)
        {
            return "�������ĥ�𣬴��ڳ������";
        }
        if (hasHarmonics(peakIndex, spectrum))
        {
            return "���ƶ��в�����ת�Ӳ�ƽ��";
        }
        if (peakFreq > 0 && peakFreq < 10 && crestFactor > 4.0)
        {
            return "��Ƶ�쳣ƫ�ߣ������������װ";
        }
        if (crestFactor > 4.0)
        {
            return "��ƫ�ߣ������һ�����";
        }
        return "״̬����";
    }

    private int findPeakIndex(double[] spectrum)
    {
        if (spectrum == null || spectrum.length == 0)
        {
            return -1;
        }
        int index = 0;
        double max = spectrum[0];
        for (int i = 1; i < spectrum.length; i++)
        {
            if (spectrum[i] > max)
            {
                max = spectrum[i];
                index = i;
            }
        }
        return index;
    }

    private boolean hasHarmonics(int fundamentalIndex, double[] spectrum)
    {
        if (fundamentalIndex <= 0 || spectrum == null || spectrum.length < fundamentalIndex * 3)
        {
            return false;
        }
        int second = fundamentalIndex * 2;
        int third = fundamentalIndex * 3;
        if (second >= spectrum.length || third >= spectrum.length)
        {
            return false;
        }
        return spectrum[fundamentalIndex] > spectrum[second] * 1.2 && spectrum[second] > spectrum[third] * 0.8;
    }

    private int nextPow2(int n)
    {
        int pow = 1;
        while (pow < n)
        {
            pow <<= 1;
        }
        return pow;
    }
}
