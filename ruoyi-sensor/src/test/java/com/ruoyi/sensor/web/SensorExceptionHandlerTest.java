package com.ruoyi.sensor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStoreUnavailableException;

class SensorExceptionHandlerTest
{
    @Test
    void timeSeriesUnavailableUsesRealHttp503()
    {
        ResponseEntity<AjaxResult> response = new SensorExceptionHandler()
            .handleTimeSeriesUnavailable(new TimeSeriesStoreUnavailableException("unavailable"));

        assertEquals(503, response.getStatusCode().value());
        assertEquals(503, response.getBody().get(AjaxResult.CODE_TAG));
        assertEquals("unavailable",
            ((Map<?, ?>) response.getBody().get(AjaxResult.DATA_TAG)).get("dataStatus"));
    }
}
