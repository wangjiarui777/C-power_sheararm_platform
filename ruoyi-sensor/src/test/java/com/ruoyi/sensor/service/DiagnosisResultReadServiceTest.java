package com.ruoyi.sensor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import com.ruoyi.sensor.mapper.EnhancedInferenceRecordMapper;
import com.ruoyi.sensor.service.timeseries.DiagnosisResultSnapshot;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStoreUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DiagnosisResultReadServiceTest
{
    @Test
    void mergesPendingMysqlRowsAndDeduplicatesByRecordId()
    {
        TimeSeriesStore store = mock(TimeSeriesStore.class);
        EnhancedInferenceRecordMapper mapper = mock(EnhancedInferenceRecordMapper.class);
        DiagnosisResultReadService service = new DiagnosisResultReadService(store, mapper);
        ReflectionTestUtils.setField(service, "readMode", "iotdb-primary");

        Date older = new Date(1000L);
        Date newer = new Date(2000L);
        DiagnosisResultSnapshot synced = snapshot(1L, older);
        EnhancedInferenceRecordEntity duplicatePending = entity(1L, older);
        EnhancedInferenceRecordEntity pending = entity(2L, newer);
        when(store.queryDiagnosisHistory("DEV-1", null, null, 10)).thenReturn(List.of(synced));
        when(mapper.selectManagedHistory("DEV-1", null, null, true, 10))
            .thenReturn(List.of(duplicatePending, pending));

        List<EnhancedInferenceRecordEntity> result = service.history("DEV-1", null, null, 10);
        assertEquals(List.of(2L, 1L), result.stream().map(EnhancedInferenceRecordEntity::getId).toList());
    }

    @Test
    void fallsBackToManagedMysqlRowsWhenIotdbIsUnavailable()
    {
        TimeSeriesStore store = mock(TimeSeriesStore.class);
        EnhancedInferenceRecordMapper mapper = mock(EnhancedInferenceRecordMapper.class);
        DiagnosisResultReadService service = new DiagnosisResultReadService(store, mapper);
        ReflectionTestUtils.setField(service, "readMode", "iotdb-primary");
        when(store.queryDiagnosisHistory(anyString(), isNull(), isNull(), anyInt()))
            .thenThrow(new TimeSeriesStoreUnavailableException("offline"));
        when(mapper.selectManagedHistory(anyString(), isNull(), isNull(), anyBoolean(), anyInt()))
            .thenReturn(List.of(entity(7L, new Date())));

        assertEquals(7L, service.latest("DEV-1").getId());
    }

    private DiagnosisResultSnapshot snapshot(Long id, Date time)
    {
        DiagnosisResultSnapshot row = new DiagnosisResultSnapshot();
        row.setRecordId(id);
        row.setDeviceCode("DEV-1");
        row.setCreateTime(time);
        return row;
    }

    private EnhancedInferenceRecordEntity entity(Long id, Date time)
    {
        EnhancedInferenceRecordEntity row = new EnhancedInferenceRecordEntity();
        row.setId(id);
        row.setDeviceCode("DEV-1");
        row.setCreateTime(time);
        return row;
    }
}
