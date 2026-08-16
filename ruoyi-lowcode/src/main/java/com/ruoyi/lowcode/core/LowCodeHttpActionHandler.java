package com.ruoyi.lowcode.core;

import java.net.URI;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.lowcode.LowCodeActionContext;
import com.ruoyi.common.lowcode.LowCodeActionHandler;

/** Executes requests against one fixed, enabled connector and an allowed path. */
@Component
public class LowCodeHttpActionHandler implements LowCodeActionHandler
{
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final LowCodeConnectorService connectors;
    private final OutboundTargetValidator outboundTargetValidator;
    @Value("${lowcode.connector.proxy-host:}")
    private String proxyHost;
    @Value("${lowcode.connector.proxy-port:0}")
    private int proxyPort;
    public LowCodeHttpActionHandler(LowCodeConnectorService connectors, OutboundTargetValidator outboundTargetValidator)
    { this.connectors = connectors; this.outboundTargetValidator = outboundTargetValidator; }
    @Override public String code() { return "connector.http"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, LowCodeActionContext context)
    {
        String connectorCode = String.valueOf(context.metadata().get("connectorCode"));
        String path = String.valueOf(context.metadata().getOrDefault("path", "/"));
        if (!path.startsWith("/") || path.contains("..") || URI.create(path).isAbsolute()) throw new IllegalArgumentException("连接器路径不合法");
        Map<String, Object> connector = connectors.getForExecution(connectorCode);
        if (!"HTTP".equals(connector.get("connectorType")) || !"ENABLED".equals(connector.get("status")))
            throw new IllegalArgumentException("HTTP连接器未启用");
        String allowed = String.valueOf(connector.getOrDefault("allowedPaths", ""));
        if (Arrays.stream(allowed.split(",")).map(String::trim).noneMatch(path::equals))
            throw new IllegalArgumentException("请求路径不在连接器白名单");
        outboundTargetValidator.requirePublicHttps(String.valueOf(connector.get("baseUrl")));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory()
        {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException
            {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        int timeout = ((Number) connector.get("timeoutMs")).intValue();
        factory.setConnectTimeout(timeout); factory.setReadTimeout(timeout);
        if (proxyHost == null || proxyHost.isBlank() || proxyPort < 1 || proxyPort > 65535)
            throw new IllegalStateException("HTTP连接器必须通过已配置的出站代理执行");
        factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        RestClient client = RestClient.builder().requestFactory(factory).baseUrl(String.valueOf(connector.get("baseUrl"))).build();
        String response = null;
        RuntimeException last = null;
        int attempts = ((Number) connector.get("retryCount")).intValue() + 1;
        for (int attempt = 0; attempt < attempts; attempt++)
        {
            try
            {
                response = client.post().uri(path).header("Idempotency-Key", context.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON).body(input).exchange((request, httpResponse) -> {
                    if (httpResponse.getStatusCode().isError())
                        throw new IllegalStateException("连接器返回 HTTP " + httpResponse.getStatusCode().value());
                    byte[] bytes = httpResponse.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("连接器响应超过 1MB 上限");
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                });
                last = null; break;
            }
            catch (RuntimeException ex) { last = ex; }
        }
        if (last != null) throw last;
        JSONObject parsed = JSON.parseObject(response);
        return parsed == null ? Map.of() : parsed;
    }
}
