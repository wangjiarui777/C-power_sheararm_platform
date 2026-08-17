package com.ruoyi.sensor.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import com.ruoyi.sensor.mapper.EnhancedInferenceRecordMapper;
import com.ruoyi.sensor.service.timeseries.DiagnosisResultSnapshot;
import com.ruoyi.sensor.service.timeseries.DiagnosisResultSnapshots;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Reads post-upgrade diagnosis history from IoTDB and merges durable pending writes. */
@Service
public class DiagnosisResultReadService
{
    private static final Logger log = LoggerFactory.getLogger(DiagnosisResultReadService.class);

    private final TimeSeriesStore timeSeriesStore;
    private final EnhancedInferenceRecordMapper recordMapper;

    @Value("${sensor.diagnosis.read-mode:iotdb-primary}")
    private String readMode;

    public DiagnosisResultReadService(TimeSeriesStore timeSeriesStore,
        EnhancedInferenceRecordMapper recordMapper)
    {
        this.timeSeriesStore = timeSeriesStore;
        this.recordMapper = recordMapper;
    }

    public EnhancedInferenceRecordEntity latest(String deviceCode)
    {
        List<EnhancedInferenceRecordEntity> rows = history(deviceCode, null, null, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<EnhancedInferenceRecordEntity> history(String deviceCode, Date from, Date to, int requestedLimit)
    {
        return history(deviceCode, null, from, to, requestedLimit);
    }

    public List<EnhancedInferenceRecordEntity> history(String deviceCode, Long pointId, Date from, Date to,
        int requestedLimit)
    {
        int limit = Math.max(1, Math.min(5000, requestedLimit));
        if (!"iotdb-primary".equalsIgnoreCase(readMode))
        {
            return managedHistory(deviceCode, pointId, from, to, false, limit);
        }

        List<EnhancedInferenceRecordEntity> primary = new ArrayList<>();
        boolean iotdbAvailable = true;
        try
        {
            for (DiagnosisResultSnapshot snapshot : queryDiagnosisHistory(deviceCode, pointId, from, to, limit))
            {
                primary.add(DiagnosisResultSnapshots.toEntity(snapshot));
            }
        }
        catch (TimeSeriesStoreUnavailableException ex)
        {
            iotdbAvailable = false;
            log.warn("Diagnosis reads are falling back to the MySQL synchronization ledger: {}", ex.getMessage());
        }

        List<EnhancedInferenceRecordEntity> mysqlRows = managedHistory(
            deviceCode, pointId, from, to, iotdbAvailable, limit);
        Map<Long, EnhancedInferenceRecordEntity> merged = new LinkedHashMap<>();
        for (EnhancedInferenceRecordEntity row : primary)
        {
            merged.put(row.getId(), row);
        }
        for (EnhancedInferenceRecordEntity row : mysqlRows)
        {
            merged.put(row.getId(), row);
        }
        List<EnhancedInferenceRecordEntity> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(EnhancedInferenceRecordEntity::getCreateTime,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return result.size() <= limit ? result : new ArrayList<>(result.subList(0, limit));
    }

    private List<DiagnosisResultSnapshot> queryDiagnosisHistory(String deviceCode, Long pointId, Date from, Date to,
        int limit)
    {
        return pointId == null
            ? timeSeriesStore.queryDiagnosisHistory(deviceCode, from, to, limit)
            : timeSeriesStore.queryDiagnosisHistory(deviceCode, pointId, from, to, limit);
    }

    private List<EnhancedInferenceRecordEntity> managedHistory(String deviceCode, Long pointId, Date from, Date to,
        boolean unsyncedOnly, int limit)
    {
        return pointId == null
            ? recordMapper.selectManagedHistory(deviceCode, from, to, unsyncedOnly, limit)
            : recordMapper.selectManagedHistoryByPoint(deviceCode, pointId, from, to, unsyncedOnly, limit);
    }
}
