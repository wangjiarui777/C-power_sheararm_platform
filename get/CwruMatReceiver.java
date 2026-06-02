import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * CWRU 样式 .mat 文件接收模块。
 *
 * 运行方式：
 * - javac CwruMatReceiver.java
 * - java CwruMatReceiver [port] [saveDir]
 */
public class CwruMatReceiver {

    private static final String HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8888;
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int MAX_HEADER_LEN = 1024 * 1024;
    private static final String DIAGNOSIS_API_BASE = "http://127.0.0.1:8080";
    private static final String ANALYSIS_CALLBACK_PATH = "/sensor/vibration/receiver/analyze";

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        File saveDir = new File(args.length >= 2 ? args[1] : "got");

        ensureSaveDir(saveDir);

        try (ServerSocket server = new ServerSocket(port)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("[系统] 接收服务已停止")));
            System.out.println("[系统] 上位机 MAT 接收端启动: " + HOST + ":" + port);
            System.out.println("[系统] 保存目录: " + saveDir.getAbsolutePath());
            System.out.println("[系统] 接收完成后将自动调用: " + DIAGNOSIS_API_BASE + ANALYSIS_CALLBACK_PATH);

            while (true) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> handleClient(socket, saveDir), "cwru-mat-client-" + socket.getPort());
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private static void ensureSaveDir(File saveDir) throws IOException {
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            throw new IOException("无法创建保存目录: " + saveDir.getAbsolutePath());
        }
    }

    private static void handleClient(Socket socket, File saveDir) {
        try (Socket client = socket;
             java.io.DataInputStream in = new java.io.DataInputStream(new BufferedInputStream(client.getInputStream()));
             java.io.DataOutputStream out = new java.io.DataOutputStream(new BufferedOutputStream(client.getOutputStream()))) {

            String magic = readLine(in);
            if (!"CWRU_MAT_V1".equals(magic)) {
                throw new IllegalArgumentException("未知协议头: " + magic);
            }

            int headerLen = in.readInt();
            if (headerLen <= 0 || headerLen > MAX_HEADER_LEN) {
                throw new IllegalArgumentException("非法头长度: " + headerLen);
            }

            byte[] headerBytes = new byte[headerLen];
            in.readFully(headerBytes);
            String headerJson = new String(headerBytes, StandardCharsets.UTF_8);

            String filename = baseName(extractJsonString(headerJson, "filename"));
            long filesize = extractJsonLong(headerJson, "filesize");
            String expectedSha256 = extractJsonString(headerJson, "sha256");

            File savePath = uniquePath(new File(saveDir, filename));
            File tmpPath = new File(savePath.getAbsolutePath() + ".part");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long received = 0;
            byte[] buffer = new byte[CHUNK_SIZE];

            try (FileOutputStream fos = new FileOutputStream(tmpPath);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                while (received < filesize) {
                    int toRead = (int) Math.min(buffer.length, filesize - received);
                    int read = in.read(buffer, 0, toRead);
                    if (read < 0) {
                        throw new IOException("文件接收中断");
                    }
                    bos.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    received += read;
                }
                bos.flush();
            }

            String actualSha256 = toHex(digest.digest());
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                File badPath = new File(savePath.getAbsolutePath() + ".bad");
                moveFile(tmpPath.toPath(), badPath.toPath());
                out.write("ERR checksum\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                System.out.println("[失败] SHA256 不一致，已保存为: " + badPath.getAbsolutePath());
                return;
            }

            moveFile(tmpPath.toPath(), savePath.toPath());
            String analysisResult = triggerAnalysis(savePath, filename, filesize, expectedSha256, headerJson);
            out.write((analysisResult.isEmpty() ? "OK" : "OK " + analysisResult).concat("\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            System.out.println("[成功] 已保存: " + savePath.getAbsolutePath());
        } catch (IOException | IllegalArgumentException | java.security.NoSuchAlgorithmException e) {
            System.out.println("[错误] " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        }
    }

    private static String triggerAnalysis(File savePath, String filename, long filesize, String sha256, String headerJson) {
        try {
            String payload = buildJsonPayload(savePath, filename, filesize, sha256, headerJson);
            return postJson(DIAGNOSIS_API_BASE + ANALYSIS_CALLBACK_PATH, payload);
        } catch (IOException ex) {
            System.out.println("[告警] 自动推理触发失败: " + ex.getMessage());
            return "";
        }
    }

    private static String buildJsonPayload(File savePath, String filename, long filesize, String sha256, String headerJson) {
        return "{"
            + "\"analysisMode\":\"current\"," 
            + "\"filePath\":\"" + escapeJson(savePath.getAbsolutePath()) + "\"," 
            + "\"filename\":\"" + escapeJson(filename) + "\"," 
            + "\"filesize\":" + filesize + ","
            + "\"sha256\":\"" + escapeJson(sha256) + "\"," 
            + "\"header\":\"" + escapeJson(headerJson) + "\""
            + "}";
    }

    private static String postJson(String urlString, String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (BufferedOutputStream bos = new BufferedOutputStream(conn.getOutputStream())) {
            bos.write(body);
            bos.flush();
        }
        int status = conn.getResponseCode();
        java.io.InputStream stream = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
        byte[] response = stream == null ? new byte[0] : stream.readAllBytes();
        String resp = new String(response, StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + resp);
        }
        return resp;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String readLine(java.io.DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int ch = in.read();
            if (ch == -1) throw new IOException("连接提前关闭");
            if (ch == '\n') break;
            if (sb.length() > 128) throw new IOException("协议头过长");
            if (ch != '\r') sb.append((char) ch);
        }
        return sb.toString();
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int start = json.indexOf(needle);
        if (start < 0) throw new IllegalArgumentException("缺少字段: " + key);
        start = json.indexOf(':', start);
        start = json.indexOf('"', start);
        int end = json.indexOf('"', start + 1);
        if (start < 0 || end < 0) throw new IllegalArgumentException("字段格式错误: " + key);
        return json.substring(start + 1, end);
    }

    private static long extractJsonLong(String json, String key) {
        String needle = "\"" + key + "\"";
        int start = json.indexOf(needle);
        if (start < 0) throw new IllegalArgumentException("缺少字段: " + key);
        start = json.indexOf(':', start) + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end));
    }

    private static File uniquePath(File path) {
        if (!path.exists()) return path;
        String name = path.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            File candidate = new File(path.getParentFile(), stem + "_" + String.format("%03d", i) + ext);
            if (!candidate.exists()) return candidate;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return new File(path.getParentFile(), stem + "_" + stamp + ext);
    }

    private static String baseName(String filename) {
        return new File(filename).getName();
    }

    private static void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (IOException ex) {
            Files.copy(source, target);
            Files.deleteIfExists(source);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
