package com.ruoyi.sensor.service.timeseries;

import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimeSeriesAnalysisServiceTest
{
    @Test
    void distinguishesNoDataFromUnavailableStorage()
    {
        TimeSeriesStore emptyStore = mock(TimeSeriesStore.class);
        when(emptyStore.loadLatestVibrationFrame(anyString(), anyInt())).thenReturn(null);
        when(emptyStore.loadRecentVibrationFrames(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        TimeSeriesAnalysisService service = new TimeSeriesAnalysisService(emptyStore);
        java.util.Map<String, Object> noData = service.loadDiagnosisData("DEV-001", 1, 120, 64);
        assertEquals("no_data", noData.get("dataStatus"));
        assertEquals(null, noData.get("diagnosis"));
        assertEquals(null, noData.get("confidence"));

        TimeSeriesStore unavailableStore = mock(TimeSeriesStore.class);
        when(unavailableStore.loadLatestVibrationFrame(anyString(), anyInt()))
                .thenThrow(new TimeSeriesStoreUnavailableException("offline"));
        assertThrows(TimeSeriesStoreUnavailableException.class,
                () -> new TimeSeriesAnalysisService(unavailableStore)
                        .loadDiagnosisData("DEV-001", 1, 120, 64));
    }
}
