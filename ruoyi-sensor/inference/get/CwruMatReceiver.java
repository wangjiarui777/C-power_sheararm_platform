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
 * CWRU 样式 .mat 文件接收模块（上位机端 TCP 接收服务）。
 *
 * <p>功能定位：</p>
 * <ul>
 *   <li>作为 TCP 服务端，监听下位机/采集端发来的 .mat 振动数据文件</li>
 *   <li>使用自定义协议 CWRU_MAT_V1 进行文件传输，包含 SHA-256 完整性校验</li>
 *   <li>接收完成后自动调用 Python 推理服务的 /infer 接口触发诊断分析</li>
 * </ul>
 *
 * <p>自定义协议格式（CWRU_MAT_V1）：</p>
 * <pre>
 *  [魔数行]   CWRU_MAT_V1\n
 *  [头长度]   4 字节 int（大端序）
 *  [JSON头]   {"filename":"...", "filesize":N, "sha256":"..."}
 *  [文件体]   filesize 字节的原始 .mat 数据
 * </pre>
 *
 * <p>编译与运行：</p>
 * <pre>
 *   javac -encoding UTF-8 CwruMatReceiver.java
 *   java  CwruMatReceiver [监听端口] [保存目录]
 * </pre>
 *
 * <p>依赖关系：</p>
 * <pre>
 *   下位机(采集端) --TCP--> CwruMatReceiver --HTTP POST--> Python /infer API
 * </pre>
 *
 * @author BiShe
 * @version 1.0
 */
public class CwruMatReceiver {

    // =========================================================================
    // 常量定义
    // =========================================================================

    /** TCP 监听地址（0.0.0.0 表示监听所有网卡） */
    private static final String HOST = "0.0.0.0";

    /** 默认 TCP 监听端口 */
    private static final int DEFAULT_PORT = 8888;

    /** 文件传输缓冲区大小：64KB（平衡内存占用与传输效率） */
    private static final int CHUNK_SIZE = 64 * 1024;

    /** JSON 协议头最大长度：1MB（防止恶意超长头部攻击） */
    private static final int MAX_HEADER_LEN = 1024 * 1024;

    /** Python 推理服务基础地址 */
    private static final String DIAGNOSIS_API_BASE = "http://127.0.0.1:5000";

    /** 推理接口路径（对应 Python 服务的 POST /infer） */
    private static final String ANALYSIS_CALLBACK_PATH = "/infer";

    // =========================================================================
    // 主入口
    // =========================================================================

    /**
     * 启动 TCP 文件接收服务。
     *
     * <p>命令行参数（均可选）：</p>
     * <ul>
     *   <li>args[0] — 监听端口，默认 8888</li>
     *   <li>args[1] — 文件保存目录，默认 ruoyi-sensor/inference/get/got/</li>
     * </ul>
     *
     * @param args 命令行参数
     * @throws Exception 启动或运行时异常
     */
    public static void main(String[] args) throws Exception {
        // 解析监听端口
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        // 解析保存目录
        File saveDir = new File(args.length >= 2 ? args[1] : defaultSaveDir().getPath());

        // 确保保存目录存在
        ensureSaveDir(saveDir);

        // 创建 TCP 服务端 Socket
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);  // 允许快速重启时复用端口（避免 TIME_WAIT 导致绑定失败）
        server.bind(new java.net.InetSocketAddress(HOST, port));

        try {
            // 注册 JVM 关闭钩子，优雅退出时打印提示
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("[系统] 接收服务已停止")));

            System.out.println("[系统] 上位机 MAT 接收端启动: " + HOST + ":" + port);
            System.out.println("[系统] 保存目录: " + saveDir.getAbsolutePath());
            System.out.println("[系统] 接收完成后将自动调用: " + DIAGNOSIS_API_BASE + ANALYSIS_CALLBACK_PATH);

            // 主循环：阻塞等待客户端连接
            while (true) {
                Socket socket = server.accept();  // 阻塞，直到有新连接
                // 每个客户端连接分配一个独立线程处理（守护线程，不阻止 JVM 退出）
                Thread worker = new Thread(() -> handleClient(socket, saveDir), "cwru-mat-client-" + socket.getPort());
                worker.setDaemon(true);
                worker.start();
            }
        } finally {
            server.close();  // 确保 ServerSocket 被关闭
        }
    }

    // =========================================================================
    // 目录相关
    // =========================================================================

    /**
     * 获取默认保存目录。
     *
     * <p>逻辑：如果当前工作目录名为 "get"，则在其下创建 "got" 子目录；
     * 如果在 inference 目录下运行，则使用 inference/get/got；如果在项目根目录
     * 运行，则优先使用 ruoyi-sensor/inference/get/got。</p>
     *
     * @return 默认保存目录
     */
    private static File defaultSaveDir() {
        File cwd = new File(System.getProperty("user.dir"));
        if ("get".equalsIgnoreCase(cwd.getName())) {
            return new File(cwd, "got");
        }
        if ("inference".equalsIgnoreCase(cwd.getName())) {
            return new File(cwd, "get/got");
        }
        File sensorInferenceDir = new File(cwd, "ruoyi-sensor/inference/get/got");
        if (sensorInferenceDir.getParentFile().exists()) {
            return sensorInferenceDir;
        }
        return new File(cwd, "get/got");
    }

    /**
     * 确保保存目录存在，不存在则递归创建。
     *
     * @param saveDir 保存目录
     * @throws IOException 目录创建失败
     */
    private static void ensureSaveDir(File saveDir) throws IOException {
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            throw new IOException("无法创建保存目录: " + saveDir.getAbsolutePath());
        }
    }

    // =========================================================================
    // 客户端连接处理（核心业务流程）
    // =========================================================================

    /**
     * 处理单个客户端连接：接收 .mat 文件 → 校验完整性 → 触发分析。
     *
     * <p>完整流程：</p>
     * <ol>
     *   <li>验证协议魔数 "CWRU_MAT_V1"</li>
     *   <li>读取 JSON 协议头（文件名、文件大小、SHA-256）</li>
     *   <li>分块接收文件数据，边接收边计算 SHA-256</li>
     *   <li>校验 SHA-256，不一致则保存为 .bad 文件并返回错误</li>
     *   <li>校验通过后原子重命名为最终文件名</li>
     *   <li>异步调用 Python 推理服务触发诊断</li>
     * </ol>
     *
     * @param socket  客户端 Socket
     * @param saveDir 文件保存目录
     */
    private static void handleClient(Socket socket, File saveDir) {
        // try-with-resources：确保 Socket 和流自动关闭
        try (Socket client = socket;
             java.io.DataInputStream in = new java.io.DataInputStream(
                 new BufferedInputStream(client.getInputStream()));
             java.io.DataOutputStream out = new java.io.DataOutputStream(
                 new BufferedOutputStream(client.getOutputStream()))) {

            // ---- 第一步：验证协议魔数 ----
            String magic = readLine(in);  // 读取第一行
            if (!"CWRU_MAT_V1".equals(magic)) {
                throw new IllegalArgumentException("未知协议头: " + magic);
            }

            // ---- 第二步：读取协议头长度和 JSON 元数据 ----
            int headerLen = in.readInt();  // 4 字节大端序 int
            if (headerLen <= 0 || headerLen > MAX_HEADER_LEN) {
                throw new IllegalArgumentException("非法头长度: " + headerLen);
            }

            byte[] headerBytes = new byte[headerLen];
            in.readFully(headerBytes);  // 确保读满 headerLen 字节
            String headerJson = new String(headerBytes, StandardCharsets.UTF_8);

            // 从 JSON 中提取文件元信息
            String filename = baseName(extractJsonString(headerJson, "filename"));  // 文件名（去除路径）
            long filesize = extractJsonLong(headerJson, "filesize");                 // 文件大小（字节）
            String expectedSha256 = extractJsonString(headerJson, "sha256");         // 预期的 SHA-256 哈希

            // ---- 第三步：准备目标路径（自动处理重名） ----
            File savePath = uniquePath(new File(saveDir, filename));
            File tmpPath = new File(savePath.getAbsolutePath() + ".part");  // 临时文件（接收中）

            // ---- 第四步：分块接收文件数据 + 计算 SHA-256 ----
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long received = 0;
            byte[] buffer = new byte[CHUNK_SIZE];

            try (FileOutputStream fos = new FileOutputStream(tmpPath);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                while (received < filesize) {
                    // 计算本次读取量：不超过缓冲区大小，也不超过剩余字节数
                    int toRead = (int) Math.min(buffer.length, filesize - received);
                    int read = in.read(buffer, 0, toRead);
                    if (read < 0) {
                        throw new IOException("文件接收中断（连接提前关闭）");
                    }
                    bos.write(buffer, 0, read);      // 写入临时文件
                    digest.update(buffer, 0, read);  // 同步更新哈希摘要
                    received += read;
                }
                bos.flush();  // 确保数据从缓冲区刷入磁盘
            }

            // ---- 第五步：SHA-256 完整性校验 ----
            String actualSha256 = toHex(digest.digest());
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                // 校验失败：保存为 .bad 文件以便排查问题
                File badPath = new File(savePath.getAbsolutePath() + ".bad");
                moveFile(tmpPath.toPath(), badPath.toPath());
                out.write("ERR checksum\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                System.out.println("[失败] SHA256 不一致，已保存为: " + badPath.getAbsolutePath());
                return;
            }

            // ---- 第六步：校验通过，原子重命名 ----
            moveFile(tmpPath.toPath(), savePath.toPath());
            out.write("OK\n".getBytes(StandardCharsets.UTF_8));  // 告知客户端接收成功
            out.flush();

            System.out.println("[成功] 已保存: " + savePath.getAbsolutePath());

            // ---- 第七步：记录接收时间并异步触发 Python 推理服务分析 ----
            Date receivedAt = new Date();
            System.out.println("[时间] 接收时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(receivedAt));
            Thread analysisThread = new Thread(
                () -> triggerAnalysis(savePath, filename, filesize, expectedSha256, headerJson, receivedAt),
                "cwru-analysis-" + socket.getPort()
            );
            analysisThread.setDaemon(true);
            analysisThread.start();

        } catch (IOException | IllegalArgumentException | java.security.NoSuchAlgorithmException e) {
            System.out.println("[错误] " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // 推理触发
    // =========================================================================

    /**
     * 调用 Python 推理服务的 /infer 接口，对刚接收的文件执行诊断分析。
     *
     * @param savePath   已保存的文件路径
     * @param filename   文件名
     * @param filesize   文件大小
     * @param sha256     SHA-256 哈希
     * @param headerJson 原始协议头 JSON
     * @return 推理服务的 JSON 响应字符串，失败时返回空字符串
     */
    private static String triggerAnalysis(File savePath, String filename, long filesize, String sha256, String headerJson, Date receivedAt) {
        try {
            String payload = buildJsonPayload(savePath, filename, filesize, sha256, headerJson, receivedAt);
            return postJson(DIAGNOSIS_API_BASE + ANALYSIS_CALLBACK_PATH, payload);
        } catch (IOException ex) {
            System.out.println("[告警] 自动推理触发失败: " + ex.getMessage());
            return "";
        }
    }

    /**
     * 构建发送给 /infer 接口的 JSON 请求体。
     *
     * <p>请求体格式：</p>
     * <pre>
     * {
     *   "filePath": "/path/to/file.mat",
     *   "analysisMode": "current",
     *   "filename": "file.mat",
     *   "batchId": null,
     *   "deviceCode": null,
     *   "sampleTime": null
     * }
     * </pre>
     *
     * @param savePath   文件绝对路径
     * @param filename   文件名
     * @param filesize   文件大小（当前未使用，预留）
     * @param sha256     哈希值（当前未使用，预留）
     * @param headerJson 原始协议头（当前未使用，预留）
     * @return JSON 字符串
     */
    private static String buildJsonPayload(File savePath, String filename, long filesize, String sha256, String headerJson, Date receivedAt) {
        String sampleTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(receivedAt);
        return "{"
            + "\"filePath\":\"" + escapeJson(savePath.getAbsolutePath()) + "\","
            + "\"analysisMode\":\"current\","
            + "\"filename\":\"" + escapeJson(filename) + "\","
            + "\"batchId\":null,"
            + "\"deviceCode\":null,"
            + "\"sampleTime\":\"" + escapeJson(sampleTime) + "\""
            + "}";
    }

    /**
     * 发送 HTTP POST 请求（JSON 格式）。
     *
     * @param urlString 目标 URL
     * @param json      JSON 请求体
     * @return 响应体字符串
     * @throws IOException 网络异常或 HTTP 状态码非 2xx
     */
    private static String postJson(String urlString, String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);   // 连接超时 5 秒
        conn.setReadTimeout(30000);     // 读取超时 30 秒（推理可能较慢）
        conn.setDoOutput(true);         // 允许输出（POST 请求必须）
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        // 发送 JSON 请求体
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (BufferedOutputStream bos = new BufferedOutputStream(conn.getOutputStream())) {
            bos.write(body);
            bos.flush();
        }

        // 读取响应
        int status = conn.getResponseCode();
        java.io.InputStream stream = status >= 200 && status < 400
            ? conn.getInputStream()      // 成功时读输入流
            : conn.getErrorStream();     // 失败时读错误流
        byte[] response = stream == null ? new byte[0] : stream.readAllBytes();
        String resp = new String(response, StandardCharsets.UTF_8);

        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + resp);
        }
        return resp;
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /**
     * 对 JSON 字符串值进行转义，防止注入。
     * 转义反斜杠、双引号、回车、换行四个特殊字符。
     *
     * @param value 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")  // 反斜杠（必须最先处理）
            .replace("\"", "\\\"")  // 双引号
            .replace("\r", "\\r")   // 回车
            .replace("\n", "\\n");  // 换行
    }

    /**
     * 从 DataInputStream 中读取一行（以 \n 结尾）。
     * 用于读取协议魔数行。
     *
     * @param in 数据输入流
     * @return 行内容（不含结尾的 \r 和 \n）
     * @throws IOException 连接提前关闭或行过长
     */
    private static String readLine(java.io.DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int ch = in.read();
            if (ch == -1) throw new IOException("连接提前关闭");
            if (ch == '\n') break;               // 读到换行符，结束
            if (sb.length() > 128) throw new IOException("协议头过长");  // 防止恶意超长行
            if (ch != '\r') sb.append((char) ch); // 忽略回车符
        }
        return sb.toString();
    }

    /**
     * 从简易 JSON 字符串中提取指定键的字符串值。
     *
     * <p>注意：这是一个极简实现，仅适用于本协议的已知 JSON 格式。
     * 不支持嵌套对象、数组、Unicode 转义等复杂 JSON 特性。</p>
     *
     * @param json JSON 字符串
     * @param key  要提取的键名
     * @return 对应的字符串值
     * @throws IllegalArgumentException 键不存在或格式错误
     */
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

    /**
     * 从简易 JSON 字符串中提取指定键的长整数值。
     *
     * @param json JSON 字符串
     * @param key  要提取的键名
     * @return 对应的长整数值
     * @throws IllegalArgumentException 键不存在或格式错误
     */
    private static long extractJsonLong(String json, String key) {
        String needle = "\"" + key + "\"";
        int start = json.indexOf(needle);
        if (start < 0) throw new IllegalArgumentException("缺少字段: " + key);
        start = json.indexOf(':', start) + 1;
        // 跳过冒号后的空白字符
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        // 读取连续数字字符
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end));
    }

    /**
     * 生成唯一的保存路径，避免覆盖已有文件。
     *
     * <p>策略（按优先级）：</p>
     * <ol>
     *   <li>目标路径不存在 → 直接使用</li>
     *   <li>添加 _001 ~ _999 后缀尝试 10000 次</li>
     *   <li>仍冲突 → 使用时间戳后缀（yyyyMMdd_HHmmss）兜底</li>
     * </ol>
     *
     * @param path 期望的文件路径
     * @return 唯一的文件路径
     */
    private static File uniquePath(File path) {
        if (!path.exists()) return path;

        // 拆分文件名和扩展名
        String name = path.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;   // 主文件名
        String ext = dot >= 0 ? name.substring(dot) : "";          // 扩展名（含点号）

        // 尝试数字后缀：file_001.mat, file_002.mat ...
        for (int i = 1; i < 10000; i++) {
            File candidate = new File(path.getParentFile(), stem + "_" + String.format("%03d", i) + ext);
            if (!candidate.exists()) return candidate;
        }

        // 数字后缀耗尽（极端情况）：使用时间戳兜底
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return new File(path.getParentFile(), stem + "_" + stamp + ext);
    }

    /**
     * 从路径中提取纯文件名，去除目录部分。
     *
     * @param filename 可能包含路径的文件名
     * @return 纯文件名
     */
    private static String baseName(String filename) {
        return new File(filename).getName();
    }

    /**
     * 移动文件（先尝试原子重命名，失败则复制+删除）。
     *
     * <p>Files.move 在同一文件系统内是原子操作；跨文件系统时可能失败，
     * 此时回退到 copy + delete 方案。</p>
     *
     * @param source 源路径
     * @param target 目标路径
     * @throws IOException 移动失败
     */
    private static void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (IOException ex) {
            // 跨文件系统时 move 可能失败，回退到复制后删除
            Files.copy(source, target);
            Files.deleteIfExists(source);
        }
    }

    /**
     * 将字节数组转换为十六进制小写字符串。
     * 用于输出 SHA-256 哈希值。
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
