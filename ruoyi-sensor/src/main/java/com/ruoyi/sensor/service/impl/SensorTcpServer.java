package com.ruoyi.sensor.service.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.ruoyi.sensor.domain.dto.ChannelFrameDTO;
import com.ruoyi.sensor.service.ChannelFrameIngestService;

@Component
public class SensorTcpServer
{
    @Value("${sensor.tcp.enabled:true}")
    private boolean enabled;

    @Value("${sensor.tcp.port:8888}")
    private int port;

    private final ChannelFrameIngestService ingestService;

    public SensorTcpServer(ChannelFrameIngestService ingestService)
    {
        this.ingestService = ingestService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start()
    {
        if (!enabled)
        {
            return;
        }
        Thread thread = new Thread(this::runServer, "sensor-tcp-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void runServer()
    {
        try (ServerSocket serverSocket = new ServerSocket(port))
        {
            while (true)
            {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> handleClient(socket), "sensor-tcp-client-" + socket.getPort());
                worker.setDaemon(true);
                worker.start();
            }
        }
        catch (IOException ex)
        {
            System.err.println("[SensorTcpServer] startup failed: " + ex.getMessage());
        }
    }

    private void handleClient(Socket socket)
    {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)))
        {
            StringBuilder batch = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.trim().isEmpty())
                {
                    continue;
                }
                if ("KEEPALIVE".equalsIgnoreCase(line.trim()))
                {
                    continue;
                }
                batch.append(line.trim()).append('\n');
                if (batch.length() > 0 && batch.length() >= 1024)
                {
                    dispatch(client, batch.toString());
                    batch.setLength(0);
                }
            }
            if (batch.length() > 0)
            {
                dispatch(client, batch.toString());
            }
        }
        catch (IOException ex)
        {
            System.err.println("[SensorTcpServer] client error: " + ex.getMessage());
        }
    }

    private void dispatch(Socket client, String payload)
    {
        ChannelFrameDTO dto = new ChannelFrameDTO();
        dto.setDeviceCode(client.getInetAddress().getHostAddress());
        dto.setBatchId(System.currentTimeMillis());
        dto.setSampleRate(1000D);
        dto.setPayload(payload.getBytes(StandardCharsets.UTF_8));
        dto.setCollectTime(new java.util.Date());
        ingestService.ingest(dto);
    }
}
