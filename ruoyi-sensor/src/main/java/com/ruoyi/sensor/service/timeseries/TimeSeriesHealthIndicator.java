package com.ruoyi.sensor.service.timeseries;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("timeSeries")
public class TimeSeriesHealthIndicator implements HealthIndicator
{
    private final TimeSeriesStore store;

    public TimeSeriesHealthIndicator(TimeSeriesStore store)
    {
        this.store = store;
    }

    @Override
    public Health health()
    {
        TimeSeriesStoreStatus status = store.getStatus();
        Health.Builder builder = !status.isEnabled() || status.isAvailable()
            ? Health.up() : Health.down();
        return builder
            .withDetail("mode", status.getMode())
            .withDetail("state", status.getState())
            .withDetail("enabled", status.isEnabled())
            .withDetail("available", status.isAvailable())
            .withDetail("failureCount", status.getFailureCount())
            .withDetail("lastSuccessfulWrite", status.getLastSuccessfulWriteTime())
            .withDetail("lastFailure", status.getLastFailureTime())
            .withDetail("lastError", status.getLastError())
            .build();
    }
}
