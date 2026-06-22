package com.ruoyi.sensor.config;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Marks legacy sensor endpoints during the compatibility window.
 */
@Component
public class LegacySensorApiDeprecationFilter extends OncePerRequestFilter
{
    private static final String SUNSET = ZonedDateTime.parse("2026-12-31T23:59:59+08:00")
            .format(DateTimeFormatter.RFC_1123_DATE_TIME);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        if (isLegacyPath(request.getRequestURI()))
        {
            response.setHeader("Deprecation", "true");
            response.setHeader("Sunset", SUNSET);
            response.setHeader("Link", "</sensor/monitoring>; rel=\"successor-version\"");
        }
        chain.doFilter(request, response);
    }

    private boolean isLegacyPath(String uri)
    {
        return uri != null && (uri.startsWith("/system/vibration")
                || uri.startsWith("/system/temperature")
                || uri.startsWith("/system/monitoring")
                || uri.startsWith("/monitoring")
                || uri.startsWith("/sensor/vibration"));
    }
}
