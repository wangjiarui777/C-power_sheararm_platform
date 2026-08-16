package com.ruoyi.framework.security.filter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ruoyi.common.utils.CollectorSecretCrypto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SensorCollectorAuthenticationFilter extends OncePerRequestFilter
{
    private static final String AUTHORITY = "sensor:collector:upload";
    private static final long MAX_SKEW_SECONDS = 300;
    private static final Set<String> PATHS = Set.of(
        "/sensor/vibration-data/upload", "/system/vibration/upload",
        "/sensor/vibration-data/batchUpload", "/system/vibration/batchUpload",
        "/sensor/temperature-data/upload", "/system/temperature/upload",
        "/sensor/diagnosis/receiver/callback", "/sensor/vibration/receiver/callback");

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final String masterKey;

    public SensorCollectorAuthenticationFilter(JdbcTemplate jdbcTemplate,
        StringRedisTemplate redisTemplate,
        @Value("${sensor.collector.master-key:}") String masterKey)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.masterKey = masterKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException
    {
        if (!isCollectorEndpoint(request.getRequestURI()))
        {
            chain.doFilter(request, response);
            return;
        }
        CachedRequest cached = new CachedRequest(request);
        try
        {
            String collectorId = requiredHeader(request, "X-Collector-Id");
            String timestamp = requiredHeader(request, "X-Timestamp");
            String nonce = requiredHeader(request, "X-Nonce");
            String contentHash = requiredHeader(request, "X-Content-SHA256").toLowerCase();
            String signature = requiredHeader(request, "X-Signature").toLowerCase();
            long epoch = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - epoch) > MAX_SKEW_SECONDS)
            {
                throw new SecurityException("collector timestamp expired");
            }
            String actualHash = CollectorSecretCrypto.sha256Hex(cached.body);
            if (!constantTimeEquals(actualHash, contentHash))
            {
                throw new SecurityException("collector body hash mismatch");
            }
            Boolean nonceClaimed = redisTemplate.opsForValue().setIfAbsent(
                "collector:nonce:" + collectorId + ":" + nonce, "1",
                MAX_SKEW_SECONDS * 2, java.util.concurrent.TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(nonceClaimed))
            {
                throw new SecurityException("collector nonce replayed");
            }
            Map<String, Object> credential = loadCredential(collectorId);
            String secret = CollectorSecretCrypto.decrypt(
                String.valueOf(credential.get("encrypted_secret")), masterKey);
            String canonical = String.join("\n", collectorId, timestamp, nonce,
                request.getMethod(), normalizedPath(request.getRequestURI()), contentHash);
            if (!constantTimeEquals(CollectorSecretCrypto.hmacHex(secret, canonical), signature))
            {
                throw new SecurityException("collector signature mismatch");
            }
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                collectorId, null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
            authentication.setDetails(Map.of(
                "collectorId", collectorId,
                "allowedDevices", String.valueOf(credential.get("allowed_devices"))));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            jdbcTemplate.update("UPDATE sensor_collector_credential SET last_online_time=?, last_ip=? WHERE collector_id=?",
                new Date(), request.getRemoteAddr(), collectorId);
            chain.doFilter(cached, response);
        }
        catch (RuntimeException ex)
        {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"invalid collector credential\"}");
        }
    }

    private Map<String, Object> loadCredential(String collectorId)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT encrypted_secret, allowed_devices FROM sensor_collector_credential "
                + "WHERE collector_id=? AND enabled=1 AND (expire_time IS NULL OR expire_time > NOW())",
            collectorId);
        if (rows.size() != 1)
        {
            throw new SecurityException("collector credential unavailable");
        }
        return rows.get(0);
    }

    private String requiredHeader(HttpServletRequest request, String name)
    {
        String value = request.getHeader(name);
        if (value == null || value.isBlank())
        {
            throw new SecurityException(name + " is required");
        }
        return value.trim();
    }

    private boolean isCollectorEndpoint(String uri)
    {
        return PATHS.contains(normalizedPath(uri));
    }

    private String normalizedPath(String uri)
    {
        return uri != null && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private boolean constantTimeEquals(String expected, String actual)
    {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class CachedRequest extends HttpServletRequestWrapper
    {
        private final byte[] body;

        private CachedRequest(HttpServletRequest request) throws IOException
        {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream()
        {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream()
            {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) {}
                @Override public int read() { return input.read(); }
            };
        }

        @Override
        public java.io.BufferedReader getReader()
        {
            return new java.io.BufferedReader(
                new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
