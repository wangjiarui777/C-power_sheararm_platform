package com.ruoyi.sensor.service.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.sensor.domain.dto.ChannelFrameDTO;
import com.ruoyi.sensor.domain.dto.TelemetryAcceptance;
import com.ruoyi.sensor.service.ChannelFramePipelineService;
import com.ruoyi.sensor.service.CollectorTcpAuthenticator;
import com.ruoyi.sensor.service.CollectorTcpAuthenticator.AuthenticatedCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Authenticated line-oriented TCP ingestion endpoint.
 *
 * The first line must be:
 * AUTH {"collectorId":"...","timestamp":...,"nonce":"...","deviceCode":"...",
 *       "frameId":"...","sequence":...,"sampleRate":1000,"signature":"..."}
 *
 * The HMAC canonical value is:
 * TCP\ncollectorId\ntimestamp\nnonce\ndeviceCode
 */
@Component
public class SensorTcpServer
{
    private static final Logger log = LoggerFactory.getLogger(SensorTcpServer.class);

    @Value("${sensor.channel-tcp.enabled:false}")
    private boolean enabled;

    @Value("${sensor.channel-tcp.bind-address:127.0.0.1}")
    private String bindAddress;

    @Value("${sensor.channel-tcp.port:8891}")
    private int port;

    @Value("${sensor.channel-tcp.socket-timeout-ms:30000}")
    private int socketTimeoutMs;

    @Value("${sensor.channel-tcp.max-frame-bytes:8388608}")
    private int maxFrameBytes;

    @Value("${sensor.channel-tcp.worker-threads:8}")
    private int workerThreads;

    private final ChannelFramePipelineService pipelineService;
    private final CollectorTcpAuthenticator authenticator;
    private ExecutorService workers;
    private volatile boolean listening;

    public SensorTcpServer(ChannelFramePipelineService pipelineService,
        CollectorTcpAuthenticator authenticator)
    {
        this.pipelineService = pipelineService;
        this.authenticator = authenticator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start()
    {
        if (!enabled)
        {
            log.info("Authenticated TCP frame receiver disabled.");
            return;
        }
        workers = Executors.newFixedThreadPool(Math.max(1, workerThreads));
        Thread thread = new Thread(this::runServer, "sensor-authenticated-tcp-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void runServer()
    {
        try (ServerSocket serverSocket = new ServerSocket())
        {
            serverSocket.bind(new InetSocketAddress(bindAddress, port));
            listening = true;
            log.info("Authenticated TCP frame receiver listening on {}:{}", bindAddress, port);
            while (!serverSocket.isClosed())
            {
                Socket socket = serverSocket.accept();
                workers.submit(() -> handleClient(socket));
            }
        }
        catch (IOException ex)
        {
            log.error("Authenticated TCP frame receiver failed", ex);
        }
        finally
        {
            listening = false;
        }
    }

    private void handleClient(Socket socket)
    {
        String remoteIp = socket.getInetAddress().getHostAddress();
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                 new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)))
        {
            client.setSoTimeout(Math.max(1000, socketTimeoutMs));
            JSONObject auth = parseAuthFrame(reader.readLine());
            AuthenticatedCollector collector = authenticator.authenticate(
                auth.getString("collectorId"),
                auth.getLongValue("timestamp"),
                auth.getString("nonce"),
                auth.getString("deviceCode"),
                auth.getString("signature"),
                remoteIp);

            StringBuilder payload = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.isBlank())
                {
                    break;
                }
                int nextBytes = payload.length() + line.length() + 1;
                if (nextBytes > maxFrameBytes)
                {
                    throw new IOException("TCP frame exceeds configured size limit");
                }
                payload.append(line.trim()).append('\n');
            }
            ChannelFrameDTO frame = toFrame(auth, collector, payload);
            TelemetryAcceptance accepted = pipelineService.accept(frame);
            writer.write(JSON.toJSONString(accepted));
            writer.newLine();
            writer.flush();
        }
        catch (Exception ex)
        {
            log.warn("Rejected TCP collector connection from {}: {}", remoteIp, ex.getMessage());
            try
            {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                writer.write("{\"code\":401,\"status\":\"REJECTED\"}");
                writer.newLine();
                writer.flush();
            }
            catch (Exception ignored)
            {
                // The peer may already have disconnected.
            }
        }
    }

    private JSONObject parseAuthFrame(String line)
    {
        if (line == null || !line.startsWith("AUTH "))
        {
            throw new SecurityException("TCP authentication frame is required");
        }
        JSONObject auth = JSON.parseObject(line.substring(5));
        if (auth == null)
        {
            throw new SecurityException("invalid TCP authentication frame");
        }
        return auth;
    }

    private ChannelFrameDTO toFrame(JSONObject auth, AuthenticatedCollector collector,
        StringBuilder payload)
    {
        if (payload.length() == 0)
        {
            throw new IllegalArgumentException("empty vibration frame");
        }
        ChannelFrameDTO frame = new ChannelFrameDTO();
        frame.setSchemaVersion("VibrationFrameEnvelope/v1");
        frame.setFrameId(valueOrDefault(auth.getString("frameId"), UUID.randomUUID().toString()));
        frame.setCollectorId(collector.collectorId());
        frame.setDeviceCode(collector.deviceCode());
        frame.setSequence(auth.getLong("sequence"));
        frame.setQuality(valueOrDefault(auth.getString("quality"), "GOOD"));
        frame.setBatchId(auth.getLong("batchId") == null
            ? System.currentTimeMillis() : auth.getLong("batchId"));
        frame.setSampleRate(auth.getDouble("sampleRate") == null
            ? 1000D : auth.getDouble("sampleRate"));
        frame.setCollectTime(auth.getDate("sampleTime") == null ? new Date() : auth.getDate("sampleTime"));
        frame.setPayload(payload.toString().getBytes(StandardCharsets.UTF_8));
        return frame;
    }

    private String valueOrDefault(String value, String fallback)
    {
        return value == null || value.isBlank() ? fallback : value;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public boolean isListening()
    {
        return listening;
    }
}
