package com.ruoyi.sensor.websocket;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import com.ruoyi.common.core.domain.model.WebSocketTicketPrincipal;
import com.ruoyi.common.core.security.WebSocketTicketService;

@Component
public class SensorWebSocketHandshakeInterceptor implements HandshakeInterceptor
{
    public static final String ATTR_USER_ID = "sensor.userId";
    public static final String ATTR_USERNAME = "sensor.username";
    public static final String ATTR_PERMISSIONS = "sensor.permissions";

    private final WebSocketTicketService ticketService;
    private final Set<String> allowedOrigins;

    public SensorWebSocketHandshakeInterceptor(WebSocketTicketService ticketService,
            @Value("${sensor.websocket.allowed-origins:http://localhost,http://127.0.0.1}") String allowedOrigins)
    {
        this.ticketService = ticketService;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes)
    {
        if (!(request instanceof ServletServerHttpRequest servletRequest) || !originAllowed(request.getHeaders().getOrigin()))
        {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String ticket = servletRequest.getServletRequest().getParameter("ticket");
        WebSocketTicketPrincipal principal = ticketService.consume(ticket);
        if (principal == null)
        {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, principal.getUserId());
        attributes.put(ATTR_USERNAME, principal.getUsername());
        attributes.put(ATTR_PERMISSIONS, principal.getPermissions());
        return true;
    }

    private boolean originAllowed(String originValue)
    {
        if (originValue == null)
        {
            return false;
        }
        try
        {
            URI origin = URI.create(originValue);
            String value = origin.getScheme() + "://" + origin.getAuthority();
            return allowedOrigins.contains(value);
        }
        catch (IllegalArgumentException ex)
        {
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception)
    {
    }
}
