package com.ruoyi.sensor.service;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ruoyi.sensor.domain.entity.DiagnosisIotdbSyncEntity;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import com.ruoyi.sensor.mapper.DiagnosisIotdbSyncMapper;
import com.ruoyi.sensor.mapper.EnhancedInferenceRecordMapper;
import com.ruoyi.sensor.service.timeseries.DiagnosisResultSnapshots;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class DiagnosisIotdbSyncService
{
    private static final Logger log = LoggerFactory.getLogger(DiagnosisIotdbSyncService.class);
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String RETRY = "RETRY";
    private static final String SYNCED = "SYNCED";

    @Autowired
    private DiagnosisIotdbSyncMapper syncMapper;

    @Autowired
    private EnhancedInferenceRecordMapper recordMapper;

    @Autowired
    private TimeSeriesStore timeSeriesStore;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${sensor.diagnosis.iotdb-sync.batch-size:100}")
    private int batchSize;

    @Value("${sensor.diagnosis.iotdb-sync.lease-seconds:60}")
    private int leaseSeconds;

    private final String workerId = UUID.randomUUID().toString();
    private volatile Date lastSuccessfulSyncTime;
    private volatile String lastSyncError;

    /** Must be invoked in the same transaction that inserted the diagnosis record. */
    public void enqueue(Long recordId)
    {
        Date now = new Date();
        DiagnosisIotdbSyncEntity sync = new DiagnosisIotdbSyncEntity();
        sync.setRecordId(recordId);
        sync.setSyncStatus(PENDING);
        sync.setAttemptCount(0);
        sync.setNextRetryTime(now);
        sync.setCreateTime(now);
        sync.setUpdateTime(now);
        syncMapper.insert(sync);
        eventPublisher.publishEvent(new DiagnosisSyncQueuedEvent(recordId));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueued(DiagnosisSyncQueuedEvent event)
    {
        processRecord(event.getRecordId());
    }

    @Scheduled(fixedDelayString = "${sensor.diagnosis.iotdb-sync.poll-delay-ms:5000}")
    public void retryPending()
    {
        Date now = new Date();
        List<DiagnosisIotdbSyncEntity> candidates = syncMapper.selectList(
            new LambdaQueryWrapper<DiagnosisIotdbSyncEntity>()
                .and(w -> w.in(DiagnosisIotdbSyncEntity::getSyncStatus, PENDING, RETRY)
                    .le(DiagnosisIotdbSyncEntity::getNextRetryTime, now)
                    .or(x -> x.eq(DiagnosisIotdbSyncEntity::getSyncStatus, PROCESSING)
                        .lt(DiagnosisIotdbSyncEntity::getLockedUntil, now)))
                .orderByAsc(DiagnosisIotdbSyncEntity::getNextRetryTime)
                .last("limit " + Math.max(1, Math.min(1000, batchSize))));
        for (DiagnosisIotdbSyncEntity candidate : candidates)
        {
            processRecord(candidate.getRecordId());
        }
    }

    void processRecord(Long recordId)
    {
        Date now = new Date();
        Date lockedUntil = new Date(now.getTime() + Math.max(10, leaseSeconds) * 1000L);
        LambdaUpdateWrapper<DiagnosisIotdbSyncEntity> claim = new LambdaUpdateWrapper<>();
        claim.eq(DiagnosisIotdbSyncEntity::getRecordId, recordId)
            .and(w -> w.in(DiagnosisIotdbSyncEntity::getSyncStatus, PENDING, RETRY)
                .or(x -> x.eq(DiagnosisIotdbSyncEntity::getSyncStatus, PROCESSING)
                    .lt(DiagnosisIotdbSyncEntity::getLockedUntil, now)))
            .set(DiagnosisIotdbSyncEntity::getSyncStatus, PROCESSING)
            .set(DiagnosisIotdbSyncEntity::getLeaseOwner, workerId)
            .set(DiagnosisIotdbSyncEntity::getLockedUntil, lockedUntil)
            .set(DiagnosisIotdbSyncEntity::getUpdateTime, now);
        if (syncMapper.update(null, claim) != 1)
        {
            return;
        }

        DiagnosisIotdbSyncEntity sync = syncMapper.selectById(recordId);
        try
        {
            EnhancedInferenceRecordEntity record = recordMapper.selectById(recordId);
            if (record == null)
            {
                throw new IllegalStateException("Diagnosis record no longer exists: " + recordId);
            }
            if (!timeSeriesStore.writeDiagnosisResult(DiagnosisResultSnapshots.fromEntity(record)))
            {
                throw new IllegalStateException("IoTDB diagnosis store is unavailable");
            }
            Date completed = new Date();
            syncMapper.update(null, new LambdaUpdateWrapper<DiagnosisIotdbSyncEntity>()
                .eq(DiagnosisIotdbSyncEntity::getRecordId, recordId)
                .eq(DiagnosisIotdbSyncEntity::getLeaseOwner, workerId)
                .set(DiagnosisIotdbSyncEntity::getSyncStatus, SYNCED)
                .set(DiagnosisIotdbSyncEntity::getSyncedTime, completed)
                .set(DiagnosisIotdbSyncEntity::getLockedUntil, null)
                .set(DiagnosisIotdbSyncEntity::getLeaseOwner, null)
                .set(DiagnosisIotdbSyncEntity::getLastError, null)
                .set(DiagnosisIotdbSyncEntity::getUpdateTime, completed));
            lastSuccessfulSyncTime = completed;
            lastSyncError = null;
        }
        catch (Exception ex)
        {
            int attempts = (sync == null || sync.getAttemptCount() == null ? 0 : sync.getAttemptCount()) + 1;
            long delaySeconds = Math.min(300L, 1L << Math.min(8, attempts));
            String error = rootMessage(ex);
            syncMapper.update(null, new LambdaUpdateWrapper<DiagnosisIotdbSyncEntity>()
                .eq(DiagnosisIotdbSyncEntity::getRecordId, recordId)
                .eq(DiagnosisIotdbSyncEntity::getLeaseOwner, workerId)
                .set(DiagnosisIotdbSyncEntity::getSyncStatus, RETRY)
                .set(DiagnosisIotdbSyncEntity::getAttemptCount, attempts)
                .set(DiagnosisIotdbSyncEntity::getNextRetryTime,
                    new Date(System.currentTimeMillis() + delaySeconds * 1000L))
                .set(DiagnosisIotdbSyncEntity::getLockedUntil, null)
                .set(DiagnosisIotdbSyncEntity::getLeaseOwner, null)
                .set(DiagnosisIotdbSyncEntity::getLastError, error)
                .set(DiagnosisIotdbSyncEntity::getUpdateTime, new Date()));
            lastSyncError = error;
            log.warn("Diagnosis IoTDB synchronization deferred, record={}, attempt={}: {}",
                recordId, attempts, error);
        }
    }

    public long pendingCount()
    {
        return syncMapper.selectCount(new LambdaQueryWrapper<DiagnosisIotdbSyncEntity>()
            .ne(DiagnosisIotdbSyncEntity::getSyncStatus, SYNCED));
    }

    public Date oldestPendingTime()
    {
        DiagnosisIotdbSyncEntity row = syncMapper.selectOne(new LambdaQueryWrapper<DiagnosisIotdbSyncEntity>()
            .ne(DiagnosisIotdbSyncEntity::getSyncStatus, SYNCED)
            .orderByAsc(DiagnosisIotdbSyncEntity::getCreateTime).last("limit 1"));
        return row == null ? null : row.getCreateTime();
    }

    public Date getLastSuccessfulSyncTime()
    {
        return lastSuccessfulSyncTime == null ? syncMapper.selectLastSyncedTime() : lastSuccessfulSyncTime;
    }

    public long totalRetryCount()
    {
        Long count = syncMapper.selectTotalRetryCount();
        return count == null ? 0L : count;
    }

    public String getLastSyncError()
    {
        return lastSyncError;
    }

    private String rootMessage(Throwable error)
    {
        Throwable current = error;
        while (current.getCause() != null)
        {
            current = current.getCause();
        }
        String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
        return message.substring(0, Math.min(1000, message.length()));
    }
}
