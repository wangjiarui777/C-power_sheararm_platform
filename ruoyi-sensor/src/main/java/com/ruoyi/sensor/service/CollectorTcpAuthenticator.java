package com.ruoyi.sensor.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.ruoyi.common.utils.CollectorSecretCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CollectorTcpAuthenticator
{
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final String masterKey;
    private final long maxSkewSeconds;

    public CollectorTcpAuthenticator(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate,
        @Value("${sensor.collector.master-key:}") String masterKey,
        @Value("${sensor.channel-tcp.max-clock-skew-seconds:300}") long maxSkewSeconds)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.masterKey = masterKey;
        this.maxSkewSeconds = Math.max(30, maxSkewSeconds);
    }

    public AuthenticatedCollector authenticate(String collectorId, long timestamp, String nonce,
        String deviceCode, String signature, String remoteIp)
    {
        if (blank(collectorId) || blank(nonce) || blank(deviceCode) || blank(signature))
        {
            throw new SecurityException("incomplete TCP authentication frame");
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > maxSkewSeconds)
        {
            throw new SecurityException("collector timestamp expired");
        }
        Map<String, Object> credential = loadCredential(collectorId);
        requireDevice(String.valueOf(credential.get("allowed_devices")), deviceCode);
        String secret = CollectorSecretCrypto.decrypt(
            String.valueOf(credential.get("encrypted_secret")), masterKey);
        String canonical = String.join("\n", "TCP", collectorId, String.valueOf(timestamp), nonce, deviceCode);
        if (!constantTimeEquals(CollectorSecretCrypto.hmacHex(secret, canonical), signature.toLowerCase()))
        {
            throw new SecurityException("collector signature mismatch");
        }
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
            "collector:tcp:nonce:" + collectorId + ":" + nonce, "1",
            maxSkewSeconds * 2, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(claimed))
        {
            throw new SecurityException("collector nonce replayed");
        }
        jdbcTemplate.update(
            "UPDATE sensor_collector_credential SET last_online_time=?, last_ip=? WHERE collector_id=?",
            new Date(), remoteIp, collectorId);
        return new AuthenticatedCollector(collectorId, deviceCode);
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

    private void requireDevice(String scope, String deviceCode)
    {
        boolean allowed = "*".equals(scope.trim()) || Arrays.stream(scope.split(","))
            .map(String::trim)
            .anyMatch(deviceCode::equals);
        if (!allowed)
        {
            throw new SecurityException("collector device scope denied");
        }
    }

    private boolean constantTimeEquals(String expected, String actual)
    {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII));
    }

    private boolean blank(String value)
    {
        return value == null || value.isBlank();
    }

    public record AuthenticatedCollector(String collectorId, String deviceCode) {}
}
