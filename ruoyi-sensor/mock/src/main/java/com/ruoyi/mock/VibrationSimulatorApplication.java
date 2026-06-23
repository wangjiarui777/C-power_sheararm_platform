package com.ruoyi.mock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reference edge gateway with durable store-and-forward semantics.
 *
 * Required environment variables:
 * PHM_COLLECTOR_ID, PHM_COLLECTOR_SECRET, PHM_DEVICE_CODE.
 */
public class VibrationSimulatorApplication
{
    private final GatewayConfig config;
    private final DiskFrameBuffer buffer;
    private final AtomicLong sequence;
    private double phase;

    public VibrationSimulatorApplication(GatewayConfig config) throws IOException
    {
        this.config = config;
        this.buffer = new DiskFrameBuffer(config.bufferDir(), config.maxBufferBytes());
        this.sequence = new AtomicLong(buffer.latestSequence());
    }

    public static void main(String[] args) throws Exception
    {
        GatewayConfig config = GatewayConfig.fromEnvironment(args);
        new VibrationSimulatorApplication(config).run();
    }

    public void run() throws InterruptedException
    {
        System.out.printf("Reliable gateway %s -> %s:%d, buffer=%s%n",
            config.collectorId(), config.host(), config.port(), config.bufferDir());
        while (!Thread.currentThread().isInterrupted())
        {
            try
            {
                buffer.store(generateFrame());
                forwardPending();
            }
            catch (Exception ex)
            {
                System.err.println("Gateway cycle failed; data remains buffered: " + ex.getMessage());
            }
            Thread.sleep(config.sampleIntervalMs());
        }
    }

    private void forwardPending()
    {
        for (Path path : buffer.pending())
        {
            try
            {
                BufferedFrame frame = buffer.read(path);
                if (send(frame))
                {
                    buffer.acknowledge(path);
                }
                else
                {
                    break;
                }
            }
            catch (Exception ex)
            {
                System.err.println("Forward failed for " + path.getFileName() + ": " + ex.getMessage());
                break;
            }
        }
    }

    private boolean send(BufferedFrame frame) throws Exception
    {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = randomNonce();
        String canonical = String.join("\n", "TCP", config.collectorId(),
            String.valueOf(timestamp), nonce, config.deviceCode());
        String signature = hmacHex(config.collectorSecret(), canonical);
        String auth = String.format(Locale.ROOT,
            "AUTH {\"collectorId\":\"%s\",\"timestamp\":%d,\"nonce\":\"%s\","
                + "\"deviceCode\":\"%s\",\"frameId\":\"%s\",\"sequence\":%d,"
                + "\"sampleRate\":%.1f,\"sampleTime\":%d,\"quality\":\"GOOD\","
                + "\"signature\":\"%s\"}",
            json(config.collectorId()), timestamp, nonce, json(config.deviceCode()),
            frame.frameId(), frame.sequence(), config.sampleRate(),
            frame.sampleTime(), signature);
        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress(config.host(), config.port()), config.connectTimeoutMs());
            socket.setSoTimeout(config.readTimeoutMs());
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer.write(auth);
            writer.newLine();
            writer.write(frame.payload());
            writer.newLine();
            writer.newLine();
            writer.flush();
            String response = reader.readLine();
            return response != null
                && (response.contains("\"queueStatus\":\"PERSISTED\"")
                    || response.contains("\"queueStatus\":\"DUPLICATE\""));
        }
    }

    private BufferedFrame generateFrame()
    {
        long next = sequence.incrementAndGet();
        long sampleTime = System.currentTimeMillis();
        phase += 0.35;
        double x = Math.sin(phase) * 2.4;
        double y = Math.sin(phase + Math.PI / 3) * 1.8;
        double z = Math.sin(phase + Math.PI / 1.7) * 3.1;
        double faultSize = Math.abs(y) + Math.abs(z);
        String payload = String.format(Locale.ROOT,
            "'__header__','1.0','[]',%.6f,%.1f,1500.0,0.75,'VIBRATION',%.6f",
            x, config.sampleRate(), faultSize);
        return new BufferedFrame(UUID.randomUUID().toString(), next, sampleTime, payload);
    }

    private static String randomNonce()
    {
        byte[] value = new byte[16];
        new SecureRandom().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String hmacHex(String secret, String canonical) throws Exception
    {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String json(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record GatewayConfig(String host, int port, String collectorId, String collectorSecret,
        String deviceCode, Path bufferDir, long maxBufferBytes, long sampleIntervalMs,
        double sampleRate, int connectTimeoutMs, int readTimeoutMs)
    {
        static GatewayConfig fromEnvironment(String[] args)
        {
            String host = value("PHM_HOST", args.length > 0 ? args[0] : "127.0.0.1");
            int port = integer("PHM_PORT", args.length > 1 ? args[1] : "8891");
            String collectorId = required("PHM_COLLECTOR_ID");
            String secret = required("PHM_COLLECTOR_SECRET");
            String deviceCode = required("PHM_DEVICE_CODE");
            Path buffer = Path.of(value("PHM_BUFFER_DIR", "./gateway-buffer"))
                .toAbsolutePath().normalize();
            return new GatewayConfig(host, port, collectorId, secret, deviceCode, buffer,
                number("PHM_MAX_BUFFER_BYTES", 10L * 1024 * 1024 * 1024),
                number("PHM_SAMPLE_INTERVAL_MS", 1000L),
                Double.parseDouble(value("PHM_SAMPLE_RATE", "25600")),
                integer("PHM_CONNECT_TIMEOUT_MS", "5000"),
                integer("PHM_READ_TIMEOUT_MS", "10000"));
        }

        private static String required(String name)
        {
            String value = System.getenv(name);
            if (value == null || value.isBlank())
            {
                throw new IllegalStateException(name + " is required");
            }
            return value.trim();
        }

        private static String value(String name, String fallback)
        {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static int integer(String name, String fallback)
        {
            return Integer.parseInt(value(name, fallback));
        }

        private static long number(String name, long fallback)
        {
            return Long.parseLong(value(name, String.valueOf(fallback)));
        }
    }

    public record BufferedFrame(String frameId, long sequence, long sampleTime, String payload) {}

    public static final class DiskFrameBuffer
    {
        private static final long MIN_RETENTION_MS = 24L * 60 * 60 * 1000;
        private final Path directory;
        private final long maxBytes;

        public DiskFrameBuffer(Path directory, long maxBytes) throws IOException
        {
            this.directory = directory.toAbsolutePath().normalize();
            this.maxBytes = Math.max(1024 * 1024, maxBytes);
            Files.createDirectories(this.directory);
        }

        public synchronized Path store(BufferedFrame frame) throws IOException
        {
            String filename = String.format(Locale.ROOT, "%020d-%s.frame",
                frame.sequence(), frame.frameId());
            Path target = directory.resolve(filename).normalize();
            if (!target.startsWith(directory))
            {
                throw new IOException("invalid frame path");
            }
            Path temporary = directory.resolve(filename + ".tmp");
            List<String> lines = List.of(
                frame.frameId(),
                String.valueOf(frame.sequence()),
                String.valueOf(frame.sampleTime()),
                Base64.getEncoder().encodeToString(frame.payload().getBytes(StandardCharsets.UTF_8)));
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try
            {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException ex)
            {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            enforceCapacity();
            return target;
        }

        public synchronized List<Path> pending()
        {
            try (var stream = Files.list(directory))
            {
                return stream.filter(path -> path.getFileName().toString().endsWith(".frame"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            }
            catch (IOException ex)
            {
                throw new IllegalStateException("Cannot list gateway buffer", ex);
            }
        }

        public BufferedFrame read(Path path) throws IOException
        {
            Path safe = path.toAbsolutePath().normalize();
            if (!safe.startsWith(directory))
            {
                throw new IOException("frame path escapes buffer");
            }
            List<String> lines = Files.readAllLines(safe, StandardCharsets.UTF_8);
            if (lines.size() != 4)
            {
                throw new IOException("corrupt buffered frame");
            }
            return new BufferedFrame(lines.get(0), Long.parseLong(lines.get(1)),
                Long.parseLong(lines.get(2)),
                new String(Base64.getDecoder().decode(lines.get(3)), StandardCharsets.UTF_8));
        }

        public void acknowledge(Path path) throws IOException
        {
            Path safe = path.toAbsolutePath().normalize();
            if (!safe.startsWith(directory))
            {
                throw new IOException("frame path escapes buffer");
            }
            Files.deleteIfExists(safe);
        }

        public long latestSequence()
        {
            return pending().stream()
                .map(path -> path.getFileName().toString().split("-", 2)[0])
                .mapToLong(value -> {
                    try { return Long.parseLong(value); }
                    catch (NumberFormatException ignored) { return 0L; }
                })
                .max().orElse(0L);
        }

        private void enforceCapacity() throws IOException
        {
            List<Path> files = pending();
            long total = 0L;
            for (Path file : files)
            {
                total += Files.size(file);
            }
            if (total <= maxBytes)
            {
                return;
            }
            long cutoff = System.currentTimeMillis() - MIN_RETENTION_MS;
            for (Path file : files)
            {
                if (total <= maxBytes || Files.getLastModifiedTime(file).toMillis() >= cutoff)
                {
                    continue;
                }
                long size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
                System.err.println("Dropped buffered frame older than 24h because disk budget was exceeded: "
                    + file.getFileName());
            }
            if (total > maxBytes)
            {
                throw new IOException("gateway buffer capacity exceeded; retained all frames younger than 24h");
            }
        }
    }
}
