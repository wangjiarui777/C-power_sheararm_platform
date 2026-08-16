package com.ruoyi.sensor.service;

import java.util.List;
import com.ruoyi.sensor.domain.dto.VibrationCsvRow;

/** Optional post-persistence hook; implementations must never break frame ingestion. */
public interface RealtimeDiagnosisHook
{
    void onSamples(String deviceCode, List<VibrationCsvRow> rows);
}
