package com.ruoyi.sensor.service.timeseries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.service.PhmDataScopeService;

@Service
public class TimeSeriesAnalysisService
{
    private final TimeSeriesStore timeSeriesStore;
    private final PhmDataScopeService dataScopeService;

    public TimeSeriesAnalysisService(TimeSeriesStore timeSeriesStore)
    {
        this(timeSeriesStore, null);
    }

    @Autowired
    public TimeSeriesAnalysisService(TimeSeriesStore timeSeriesStore, PhmDataScopeService dataScopeService)
    {
        this.timeSeriesStore = timeSeriesStore;
        this.dataScopeService = dataScopeService;
    }

    public Map<String, Object> loadDiagnosisData(String deviceCode, Integer channelId, int timeLimit, int fftLimit)
    {
        if (dataScopeService != null && (deviceCode == null || deviceCode.isBlank()
            || dataScopeService.getDevice(scopedDevice(deviceCode)) == null))
        {
            return emptyResult(deviceCode, channelId);
        }
        VibrationFrameSnapshot latest = timeSeriesStore.loadLatestVibrationFrame(deviceCode, channelId);
        List<VibrationFrameSnapshot> recent = timeSeriesStore.loadRecentVibrationFrames(deviceCode, channelId,
                Math.max(1, Math.min(timeLimit, 24)));

        List<Double> waveform = latest == null ? new ArrayList<>() : trim(latest.getWaveform(), timeLimit);
        List<Double> spectrum = latest == null ? new ArrayList<>() : trim(latest.getSpectrum(), fftLimit);
        Double freqStep = latest == null ? null : latest.getFreqStep();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", latest == null ? deviceCode : latest.getDeviceCode());
        result.put("sampleTime", latest == null ? null : latest.getSampleTime());
        result.put("requestedDeviceCode", deviceCode);
        result.put("channelId", channelId == null || channelId <= 0 ? 1 : channelId);
        result.put("waveform", waveform);
        result.put("frequencyAxis", buildFrequencyAxis(spectrum.size(), freqStep));
        result.put("spectrum", spectrum);
        result.put("waterfall", buildWaterfall(recent, fftLimit));
        result.put("dataStatus", latest == null && recent.isEmpty() ? "no_data" : "available");
        result.put("confidence", null);
        result.put("diagnosis", null);
        result.put("diagnosisDetail", waveform.isEmpty() ? null : "时域与频域数据来自时序存储，未执行模型诊断");
        result.put("rms", waveform.isEmpty() ? null : rms(waveform));
        result.put("peak", waveform.isEmpty() ? null : peak(waveform));
        return result;
    }

    private PhmDeviceScopeQuery scopedDevice(String deviceCode)
    {
        PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
        query.setDeviceCode(deviceCode.trim());
        return query;
    }

    private Map<String, Object> emptyResult(String deviceCode, Integer channelId)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("sampleTime", null);
        result.put("requestedDeviceCode", deviceCode);
        result.put("channelId", channelId == null || channelId <= 0 ? 1 : channelId);
        result.put("waveform", new ArrayList<>());
        result.put("frequencyAxis", new ArrayList<>());
        result.put("spectrum", new ArrayList<>());
        result.put("waterfall", new ArrayList<>());
        result.put("dataStatus", "no_data");
        result.put("confidence", null);
        result.put("diagnosis", null);
        result.put("diagnosisDetail", null);
        result.put("rms", null);
        result.put("peak", null);
        return result;
    }

    private List<Integer> buildFrequencyAxis(int size, Double freqStep)
    {
        List<Integer> axis = new ArrayList<>(size);
        double step = freqStep == null || freqStep <= 0 ? 1D : freqStep;
        for (int i = 0; i < size; i++)
        {
            axis.add((int) Math.round(i * step));
        }
        return axis;
    }

    private List<Map<String, Object>> buildWaterfall(List<VibrationFrameSnapshot> frames, int fftLimit)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = frames.size() - 1; i >= 0; i--)
        {
            VibrationFrameSnapshot frame = frames.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", frame.getSampleTime());
            row.put("channelId", frame.getChannelId());
            row.put("spectrum", trim(frame.getSpectrum(), fftLimit));
            row.put("frequencyAxis", buildFrequencyAxis(
                    Math.min(frame.getSpectrum() == null ? 0 : frame.getSpectrum().size(), fftLimit),
                    frame.getFreqStep()));
            rows.add(row);
        }
        return rows;
    }

    private List<Double> trim(List<Double> values, int limit)
    {
        if (values == null || values.isEmpty())
        {
            return new ArrayList<>();
        }
        int safeLimit = Math.max(1, limit);
        return new ArrayList<>(values.subList(0, Math.min(values.size(), safeLimit)));
    }

    private double rms(List<Double> waveform)
    {
        if (waveform == null || waveform.isEmpty())
        {
            return 0D;
        }
        double sum = 0D;
        for (Double value : waveform)
        {
            double safe = value == null ? 0D : value;
            sum += safe * safe;
        }
        return Math.sqrt(sum / waveform.size());
    }

    private double peak(List<Double> waveform)
    {
        double peak = 0D;
        if (waveform == null)
        {
            return peak;
        }
        for (Double value : waveform)
        {
            peak = Math.max(peak, Math.abs(value == null ? 0D : value));
        }
        return peak;
    }

}
