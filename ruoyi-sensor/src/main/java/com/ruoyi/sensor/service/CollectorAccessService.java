package com.ruoyi.sensor.service;

import java.util.Arrays;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CollectorAccessService
{
    public void requireDevice(String deviceCode)
    {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof Map))
        {
            throw new SecurityException("collector scope unavailable");
        }
        Object value = ((Map<?, ?>) authentication.getDetails()).get("allowedDevices");
        String scope = value == null ? "" : String.valueOf(value);
        boolean allowed = "*".equals(scope.trim()) || Arrays.stream(scope.split(","))
            .map(String::trim)
            .anyMatch(deviceCode::equals);
        if (!allowed)
        {
            throw new SecurityException("collector is not allowed to upload for device " + deviceCode);
        }
    }
}
