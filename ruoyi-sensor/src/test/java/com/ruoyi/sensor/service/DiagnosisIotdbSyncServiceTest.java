package com.ruoyi.sensor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import com.ruoyi.sensor.domain.entity.DiagnosisIotdbSyncEntity;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import com.ruoyi.sensor.mapper.DiagnosisIotdbSyncMapper;
import com.ruoyi.sensor.mapper.EnhancedInferenceRecordMapper;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class DiagnosisIotdbSyncServiceTest
{
    private DiagnosisIotdbSyncService service;
    private DiagnosisIotdbSyncMapper syncMapper;
    private EnhancedInferenceRecordMapper recordMapper;
    private TimeSeriesStore store;
    private ApplicationEventPublisher publisher;

    @BeforeEach
    void setUp()
    {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace("com.ruoyi.sensor.mapper.DiagnosisIotdbSyncMapper");
        TableInfoHelper.initTableInfo(assistant, DiagnosisIotdbSyncEntity.class);
        service = new DiagnosisIotdbSyncService();
        syncMapper = mock(DiagnosisIotdbSyncMapper.class);
        recordMapper = mock(EnhancedInferenceRecordMapper.class);
        store = mock(TimeSeriesStore.class);
        publisher = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(service, "syncMapper", syncMapper);
        ReflectionTestUtils.setField(service, "recordMapper", recordMapper);
        ReflectionTestUtils.setField(service, "timeSeriesStore", store);
        ReflectionTestUtils.setField(service, "eventPublisher", publisher);
        ReflectionTestUtils.setField(service, "batchSize", 100);
        ReflectionTestUtils.setField(service, "leaseSeconds", 60);
    }

    @Test
    void enqueueCreatesPendingLedgerAndPublishesAfterCommitEvent()
    {
        service.enqueue(42L);

        ArgumentCaptor<DiagnosisIotdbSyncEntity> captor = ArgumentCaptor.forClass(DiagnosisIotdbSyncEntity.class);
        verify(syncMapper).insert(captor.capture());
        DiagnosisIotdbSyncEntity row = captor.getValue();
        assertEquals(42L, row.getRecordId());
        assertEquals("PENDING", row.getSyncStatus());
        assertNotNull(row.getNextRetryTime());
        verify(publisher).publishEvent(any(DiagnosisSyncQueuedEvent.class));
    }

    @Test
    void unavailableIotdbIsConvertedToRetryWithoutThrowing()
    {
        DiagnosisIotdbSyncEntity sync = new DiagnosisIotdbSyncEntity();
        sync.setRecordId(42L);
        sync.setAttemptCount(0);
        EnhancedInferenceRecordEntity record = new EnhancedInferenceRecordEntity();
        record.setId(42L);
        record.setCreateTime(new Date());
        when(syncMapper.update(isNull(), any())).thenReturn(1);
        when(syncMapper.selectById(42L)).thenReturn(sync);
        when(recordMapper.selectById(42L)).thenReturn(record);
        when(store.writeDiagnosisResult(any())).thenReturn(false);

        service.processRecord(42L);

        verify(store).writeDiagnosisResult(any());
        verify(syncMapper, times(2)).update(isNull(), any());
    }
}
