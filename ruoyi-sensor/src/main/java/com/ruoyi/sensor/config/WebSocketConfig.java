package com.ruoyi.sensor.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.ruoyi.sensor.websocket.SensorWebSocketHandler;
import com.ruoyi.sensor.websocket.SensorWebSocketHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer
{
    private final SensorWebSocketHandler sensorWebSocketHandler;
    private final SensorWebSocketHandshakeInterceptor handshakeInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(SensorWebSocketHandler sensorWebSocketHandler,
                           SensorWebSocketHandshakeInterceptor handshakeInterceptor,
                           @Value("${sensor.websocket.allowed-origins:http://localhost}") String allowedOrigins)
    {
        this.sensorWebSocketHandler = sensorWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        registry.addHandler(sensorWebSocketHandler, "/ws/sensor", "/ws/monitoring")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
