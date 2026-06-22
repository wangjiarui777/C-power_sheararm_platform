package com.ruoyi.sensor.websocket;

import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import com.ruoyi.common.core.domain.model.WebSocketTicketPrincipal;
import com.ruoyi.common.core.security.WebSocketTicketService;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensorWebSocketHandshakeInterceptorTest
{
    @Test
    void rejectsMissingTicketAndWrongOrigin()
    {
        WebSocketTicketService tickets = mock(WebSocketTicketService.class);
        SensorWebSocketHandshakeInterceptor interceptor = new SensorWebSocketHandshakeInterceptor(
                tickets, "http://localhost:9528");

        assertFalse(handshake(interceptor, "http://localhost:9528", null));
        assertFalse(handshake(interceptor, "http://evil.example", "valid-ticket"));
    }

    @Test
    void acceptsAndConsumesValidTicket()
    {
        WebSocketTicketService tickets = mock(WebSocketTicketService.class);
        WebSocketTicketPrincipal principal = new WebSocketTicketPrincipal();
        principal.setUserId(1L);
        principal.setUsername("admin");
        principal.setPermissions(Set.of("sensor:monitoring:view"));
        when(tickets.consume("valid-ticket")).thenReturn(principal);

        SensorWebSocketHandshakeInterceptor interceptor = new SensorWebSocketHandshakeInterceptor(
                tickets, "http://localhost:9528");

        assertTrue(handshake(interceptor, "http://localhost:9528", "valid-ticket"));
    }

    private boolean handshake(SensorWebSocketHandshakeInterceptor interceptor, String origin, String ticket)
    {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/monitoring");
        servletRequest.addHeader("Origin", origin);
        if (ticket != null)
        {
            servletRequest.setParameter("ticket", ticket);
        }
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        return interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                mock(WebSocketHandler.class),
                new HashMap<>());
    }
}
