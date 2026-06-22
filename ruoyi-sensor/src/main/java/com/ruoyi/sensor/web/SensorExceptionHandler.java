package com.ruoyi.sensor.web;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStoreUnavailableException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.ruoyi.sensor.web")
public class SensorExceptionHandler
{
    @ExceptionHandler(TimeSeriesStoreUnavailableException.class)
    public ResponseEntity<AjaxResult> handleTimeSeriesUnavailable(TimeSeriesStoreUnavailableException exception)
    {
        AjaxResult body = AjaxResult.error(HttpStatus.SERVICE_UNAVAILABLE.value(), exception.getMessage());
        body.put(AjaxResult.DATA_TAG, Map.of("dataStatus", "unavailable"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
