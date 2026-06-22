package com.ruoyi.sensor.service.timeseries;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class TimeSeriesStoreUnavailableException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public TimeSeriesStoreUnavailableException(String message)
    {
        super(message);
    }

    public TimeSeriesStoreUnavailableException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
