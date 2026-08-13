package com.ruoyi.sensor.service.timeseries;

import java.util.Date;
import com.ruoyi.sensor.service.DiagnosisIotdbSyncService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("diagnosisIotdbSync")
public class DiagnosisSyncHealthIndicator implements HealthIndicator
{
    private final DiagnosisIotdbSyncService syncService;

    @Value("${sensor.diagnosis.iotdb-sync.degraded-after-seconds:60}")
    private long degradedAfterSeconds;

    public DiagnosisSyncHealthIndicator(DiagnosisIotdbSyncService syncService)
    {
        this.syncService = syncService;
    }

    @Override
    public Health health()
    {
        long pending = syncService.pendingCount();
        Date oldest = syncService.oldestPendingTime();
        long ageSeconds = oldest == null ? 0L : Math.max(0L, (System.currentTimeMillis() - oldest.getTime()) / 1000L);
        Health.Builder builder = pending > 0 && ageSeconds > degradedAfterSeconds
            ? Health.status("DEGRADED") : Health.up();
        return builder.withDetail("pendingCount", pending)
            .withDetail("retryCount", syncService.totalRetryCount())
            .withDetail("oldestPendingAgeSeconds", ageSeconds)
            .withDetail("lastSuccessfulSyncTime", syncService.getLastSuccessfulSyncTime())
            .withDetail("lastError", syncService.getLastSyncError())
            .build();
    }
}
