package com.ruoyi.sensor.service.timeseries;

import java.util.Date;
import java.util.List;
import java.util.Map;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.domain.dto.VibrationFrameEnvelope;

public interface TimeSeriesStore
{
    TimeSeriesStoreStatus getStatus();

    boolean writeTelemetry(TelemetryEnvelope envelope);

    boolean writeVibrationFrame(VibrationFrameEnvelope envelope);

    boolean writeDiagnosisResult(DiagnosisResultSnapshot result);

    List<Map<String, Object>> queryTelemetryTrend(String deviceCode, String pointCode, String metricCode,
                                                  Date from, Date to, int limit);

    VibrationFrameSnapshot loadLatestVibrationFrame(String deviceCode, Integer channelId);

    List<VibrationFrameSnapshot> loadRecentVibrationFrames(String deviceCode, Integer channelId, int limit);

    DiagnosisResultSnapshot loadLatestDiagnosis(String deviceCode);

    List<DiagnosisResultSnapshot> queryDiagnosisHistory(String deviceCode, Date from, Date to, int limit);

    List<DiagnosisResultSnapshot> queryDiagnosisHistory(String deviceCode, Long pointId, Date from, Date to,
                                                        int limit);
}
