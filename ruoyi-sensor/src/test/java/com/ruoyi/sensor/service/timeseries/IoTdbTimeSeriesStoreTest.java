package com.ruoyi.sensor.service.timeseries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IoTdbTimeSeriesStoreTest
{
    @Test
    void disabledStoreReportsDisabledWithoutConnecting()
    {
        IoTdbTimeSeriesStore store = configuredStore(false, "monitoring", "127.0.0.1:1");
        try
        {
            store.init();
            assertEquals("DISABLED", store.getStatus().getState());
            assertFalse(store.getStatus().isAvailable());
        }
        finally
        {
            store.destroy();
        }
    }

    @Test
    void invalidConfigurationFailsInBackgroundAndDoesNotBlockCallers() throws Exception
    {
        IoTdbTimeSeriesStore store = configuredStore(true, "invalid-name", "127.0.0.1:1");
        try
        {
            store.init();
            assertTimeout(Duration.ofMillis(500), () -> {
                assertFalse(store.writeTelemetry(new TelemetryEnvelope()));
                assertThrows(TimeSeriesStoreUnavailableException.class,
                    () -> store.loadLatestVibrationFrame("DEV-001", 1));
            });

            waitForFailure(store);
            TimeSeriesStoreStatus status = store.getStatus();
            assertEquals("UNAVAILABLE", status.getState());
            assertTrue(status.getFailureCount() > 0);
            assertNotNull(status.getLastConnectionAttemptTime());
            assertNotNull(status.getLastFailureTime());
            assertTrue(status.getLastError().contains("Invalid IoTDB database name"));
        }
        finally
        {
            store.destroy();
        }
    }

    @Test
    void bootstrapPoolIsUnboundAndRuntimePoolTargetsConfiguredDatabase()
    {
        IoTdbTimeSeriesStore store = configuredStore(true, "monitoring", "127.0.0.1:6667");
        ITableSessionPool bootstrap = ReflectionTestUtils.invokeMethod(store, "buildPool", (String) null);
        ITableSessionPool runtime = ReflectionTestUtils.invokeMethod(store, "buildPool", "monitoring");
        try
        {
            Object bootstrapDelegate = ReflectionTestUtils.getField(bootstrap, "sessionPool");
            Object runtimeDelegate = ReflectionTestUtils.getField(runtime, "sessionPool");
            assertEquals(null, ReflectionTestUtils.getField(bootstrapDelegate, "database"));
            assertEquals("monitoring", ReflectionTestUtils.getField(runtimeDelegate, "database"));
        }
        finally
        {
            bootstrap.close();
            runtime.close();
            store.destroy();
        }
    }

    private IoTdbTimeSeriesStore configuredStore(boolean enabled, String database, String nodes)
    {
        IoTdbTimeSeriesStore store = new IoTdbTimeSeriesStore();
        ReflectionTestUtils.setField(store, "enabled", enabled);
        ReflectionTestUtils.setField(store, "database", database);
        ReflectionTestUtils.setField(store, "nodeUrls", nodes);
        ReflectionTestUtils.setField(store, "username", "root");
        ReflectionTestUtils.setField(store, "password", "root");
        ReflectionTestUtils.setField(store, "queryTimeoutMs", 500L);
        ReflectionTestUtils.setField(store, "connectionTimeoutMs", 500);
        ReflectionTestUtils.setField(store, "waitSessionTimeoutMs", 500L);
        ReflectionTestUtils.setField(store, "maxRetryCount", 0);
        ReflectionTestUtils.setField(store, "retryIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "reconnectIntervalSeconds", 30L);
        ReflectionTestUtils.setField(store, "rpcCompression", true);
        ReflectionTestUtils.setField(store, "redirection", true);
        ReflectionTestUtils.setField(store, "autoFetchNodes", true);
        ReflectionTestUtils.setField(store, "useSsl", false);
        ReflectionTestUtils.setField(store, "trustStore", "");
        ReflectionTestUtils.setField(store, "trustStorePassword", "");
        ReflectionTestUtils.setField(store, "fetchSize", 16);
        ReflectionTestUtils.setField(store, "sessionPoolSize", 1);
        ReflectionTestUtils.setField(store, "telemetryTtlDays", 1);
        ReflectionTestUtils.setField(store, "frameTtlDays", 1);
        ReflectionTestUtils.setField(store, "timestampPrecision", "us");
        return store;
    }

    private void waitForFailure(IoTdbTimeSeriesStore store) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 3000L;
        while ((store.getStatus().getFailureCount() == 0
                || "CONNECTING".equals(store.getStatus().getState()))
                && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20L);
        }
    }
}
