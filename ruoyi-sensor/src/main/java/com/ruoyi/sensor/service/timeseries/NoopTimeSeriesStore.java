package com.ruoyi.sensor.service.timeseries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.domain.dto.VibrationFrameEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "sensor", name = "store-type", havingValue = "noop")
public class NoopTimeSeriesStore implements TimeSeriesStore
{
    @Override
    public TimeSeriesStoreStatus getStatus()
    {
        return new TimeSeriesStoreStatus("noop", false, false, null, 0);
    }

    @Override
    public boolean writeTelemetry(TelemetryEnvelope envelope)
    {
        return false;
    }

    @Override
    public boolean writeVibrationFrame(VibrationFrameEnvelope envelope)
    {
        return false;
    }

    @Override
    public boolean writeDiagnosisResult(DiagnosisResultSnapshot result)
    {
        return false;
    }

    @Override
    public List<Map<String, Object>> queryTelemetryTrend(String deviceCode, String pointCode, String metricCode,
                                                         Date from, Date to, int limit)
    {
        throw new TimeSeriesStoreUnavailableException("Time-series storage is disabled");
    }

    @Override
    public VibrationFrameSnapshot loadLatestVibrationFrame(String deviceCode, Integer channelId)
    {
        throw new TimeSeriesStoreUnavailableException("Time-series storage is disabled");
    }

    @Override
    public List<VibrationFrameSnapshot> loadRecentVibrationFrames(String deviceCode, Integer channelId, int limit)
    {
        throw new TimeSeriesStoreUnavailableException("Time-series storage is disabled");
    }

    @Override
    public DiagnosisResultSnapshot loadLatestDiagnosis(String deviceCode)
    {
        throw new TimeSeriesStoreUnavailableException("Time-series storage is disabled");
    }

    @Override
    public List<DiagnosisResultSnapshot> queryDiagnosisHistory(String deviceCode, Date from, Date to, int limit)
    {
        throw new TimeSeriesStoreUnavailableException("Time-series storage is disabled");
    }

    @Override
    public List<DiagnosisResultSnapshot> queryDiagnosisHistory(String deviceCode, Long pointId, Date from, Date to,
                                                               int limit)
    {
        throw new TimeSeriesStoreUnavailableException("Time-series storage is disabled");
    }
}
