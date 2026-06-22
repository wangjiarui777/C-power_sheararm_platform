package com.ruoyi.sensor.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import com.ruoyi.common.core.domain.model.WebSocketTicketPrincipal;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.core.security.WebSocketTicketService;
import org.junit.jupiter.api.Test;

class WebSocketTicketServiceTest
{
    @Test
    void ticketPrincipalIsRestoredFromExplicitJson()
    {
        RedisCache redis = mock(RedisCache.class);
        when(redis.getCacheObject(anyString())).thenReturn(
            "{\"userId\":1,\"username\":\"admin\",\"permissions\":[\"sensor:monitoring:view\"]}");
        WebSocketTicketPrincipal principal = new WebSocketTicketService(redis).peek("ticket");

        assertEquals("admin", principal.getUsername());
        assertEquals(Set.of("sensor:monitoring:view"), principal.getPermissions());
    }
}
