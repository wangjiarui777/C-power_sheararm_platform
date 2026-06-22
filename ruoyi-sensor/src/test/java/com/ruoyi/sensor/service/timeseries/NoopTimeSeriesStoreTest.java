package com.ruoyi.sensor.service.timeseries;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoopTimeSeriesStoreTest
{
    @Test
    void disabledStoreDoesNotPretendThatAnEmptyQuerySucceeded()
    {
        NoopTimeSeriesStore store = new NoopTimeSeriesStore();

        assertFalse(store.getStatus().isEnabled());
        assertThrows(TimeSeriesStoreUnavailableException.class,
                () -> store.loadLatestVibrationFrame("DEV-001", 1));
    }
}
