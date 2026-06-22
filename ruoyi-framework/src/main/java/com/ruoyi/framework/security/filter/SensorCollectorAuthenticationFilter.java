package com.ruoyi.framework.security.filter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.common.utils.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates machine-to-machine sensor uploads with a dedicated credential.
 * 使用精确路径匹配防止前缀绕过。
 */
@Component
public class SensorCollectorAuthenticationFilter extends OncePerRequestFilter
{
    private static final String COLLECTOR_HEADER = "X-Collector-Token";
    private static final String COLLECTOR_AUTHORITY = "sensor:collector:upload";

    /** 采集器专用端点（精确匹配） */
    private static final Set<String> COLLECTOR_PATHS = Set.of(
        "/sensor/vibration-data/upload",
        "/system/vibration/upload",
        "/sensor/vibration-data/batchUpload",
        "/system/vibration/batchUpload",
        "/sensor/temperature-data/upload",
        "/system/temperature/upload",
        "/sensor/diagnosis/receiver/callback",
        "/sensor/vibration/receiver/callback"
    );

    @Value("${sensor.collector.token:}")
    private String configuredToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        String suppliedToken = request.getHeader(COLLECTOR_HEADER);
        if (isCollectorEndpoint(request.getRequestURI())
                && StringUtils.isNotEmpty(configuredToken)
                && constantTimeEquals(configuredToken, suppliedToken)
                && SecurityContextHolder.getContext().getAuthentication() == null)
        {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "sensor-collector", null, List.of(new SimpleGrantedAuthority(COLLECTOR_AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    private boolean isCollectorEndpoint(String uri)
    {
        if (uri == null) return false;
        // 标准化路径：移除尾部斜杠
        String normalized = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        return COLLECTOR_PATHS.contains(normalized);
    }

    private boolean constantTimeEquals(String expected, String actual)
    {
        if (actual == null || expected.length() != actual.length())
        {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expected.length(); i++)
        {
            result |= expected.charAt(i) ^ actual.charAt(i);
        }
        return result == 0;
    }
}
