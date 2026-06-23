package com.ruoyi.sensor.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.ruoyi.common.utils.CollectorSecretCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectorTcpAuthenticatorTest
{
    private static final String MASTER_KEY = "collector-master-key-at-least-32-bytes";
    private static final String SECRET = "collector-secret-at-least-32-bytes";

    @Test
    void authenticatesSignedFrameWithinCollectorDeviceScope()
    {
        Fixture fixture = fixture("DEV-001,DEV-002", true);
        long timestamp = Instant.now().getEpochSecond();
        String signature = signature("GW-01", timestamp, "nonce-1", "DEV-001");

        CollectorTcpAuthenticator.AuthenticatedCollector result = fixture.authenticator.authenticate(
            "GW-01", timestamp, "nonce-1", "DEV-001", signature, "10.0.0.8");

        assertEquals("GW-01", result.collectorId());
        assertEquals("DEV-001", result.deviceCode());
    }

    @Test
    void rejectsDeviceOutsideCredentialScopeBeforeAcceptingPayload()
    {
        Fixture fixture = fixture("DEV-002", true);
        long timestamp = Instant.now().getEpochSecond();

        assertThrows(SecurityException.class, () -> fixture.authenticator.authenticate(
            "GW-01", timestamp, "nonce-2", "DEV-001",
            signature("GW-01", timestamp, "nonce-2", "DEV-001"), "10.0.0.8"));
    }

    @Test
    void rejectsReplayedNonce()
    {
        Fixture fixture = fixture("*", false);
        long timestamp = Instant.now().getEpochSecond();

        assertThrows(SecurityException.class, () -> fixture.authenticator.authenticate(
            "GW-01", timestamp, "nonce-used", "DEV-001",
            signature("GW-01", timestamp, "nonce-used", "DEV-001"), "10.0.0.8"));
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(String allowedDevices, boolean nonceAccepted)
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
            .thenReturn(nonceAccepted);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
            "encrypted_secret", CollectorSecretCrypto.encrypt(SECRET, MASTER_KEY),
            "allowed_devices", allowedDevices)));
        return new Fixture(new CollectorTcpAuthenticator(jdbc, redis, MASTER_KEY, 300));
    }

    private String signature(String collectorId, long timestamp, String nonce, String deviceCode)
    {
        return CollectorSecretCrypto.hmacHex(SECRET,
            String.join("\n", "TCP", collectorId, String.valueOf(timestamp), nonce, deviceCode));
    }

    private record Fixture(CollectorTcpAuthenticator authenticator) {}
}
