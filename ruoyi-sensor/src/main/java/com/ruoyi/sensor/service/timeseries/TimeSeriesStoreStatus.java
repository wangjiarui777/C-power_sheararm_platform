package com.ruoyi.sensor.service.timeseries;

import java.util.Date;

public class TimeSeriesStoreStatus
{
    private final String mode;
    private final String state;
    private final boolean enabled;
    private final boolean available;
    private final Date lastSuccessfulWriteTime;
    private final Date lastSuccessfulOperationTime;
    private final Date lastConnectionAttemptTime;
    private final Date lastFailureTime;
    private final long failureCount;
    private final String lastError;

    public TimeSeriesStoreStatus(String mode, boolean enabled, boolean available,
            Date lastSuccessfulWriteTime, long failureCount)
    {
        this(mode, enabled ? (available ? "AVAILABLE" : "UNAVAILABLE") : "DISABLED",
                enabled, available, lastSuccessfulWriteTime, null, null, null, failureCount, null);
    }

    public TimeSeriesStoreStatus(String mode, String state, boolean enabled, boolean available,
            Date lastSuccessfulWriteTime, Date lastSuccessfulOperationTime,
            Date lastConnectionAttemptTime, Date lastFailureTime, long failureCount, String lastError)
    {
        this.mode = mode;
        this.state = state;
        this.enabled = enabled;
        this.available = available;
        this.lastSuccessfulWriteTime = lastSuccessfulWriteTime;
        this.lastSuccessfulOperationTime = lastSuccessfulOperationTime;
        this.lastConnectionAttemptTime = lastConnectionAttemptTime;
        this.lastFailureTime = lastFailureTime;
        this.failureCount = failureCount;
        this.lastError = lastError;
    }

    public String getMode() { return mode; }
    public String getState() { return state; }
    public boolean isEnabled() { return enabled; }
    public boolean isAvailable() { return available; }
    public Date getLastSuccessfulWriteTime() { return lastSuccessfulWriteTime; }
    public Date getLastSuccessfulOperationTime() { return lastSuccessfulOperationTime; }
    public Date getLastConnectionAttemptTime() { return lastConnectionAttemptTime; }
    public Date getLastFailureTime() { return lastFailureTime; }
    public long getFailureCount() { return failureCount; }
    public String getLastError() { return lastError; }
}
