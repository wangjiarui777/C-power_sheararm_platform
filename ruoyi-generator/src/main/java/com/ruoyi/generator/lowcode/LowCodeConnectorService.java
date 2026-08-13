package com.ruoyi.generator.lowcode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class LowCodeConnectorService
{
    private final JdbcTemplate jdbc;
    private final OutboundTargetValidator outboundTargetValidator;

    public LowCodeConnectorService(JdbcTemplate jdbc, OutboundTargetValidator outboundTargetValidator)
    { this.jdbc = jdbc; this.outboundTargetValidator = outboundTargetValidator; }

    public List<Map<String, Object>> list()
    {
        return jdbc.queryForList("""
            SELECT id,connector_code connectorCode,connector_name connectorName,connector_type connectorType,
              base_url baseUrl,allowed_paths allowedPaths,timeout_ms timeoutMs,retry_count retryCount,
              CASE WHEN auth_ref IS NULL OR auth_ref='' THEN FALSE ELSE TRUE END credentialConfigured,
              status,create_by createBy,create_time createTime,update_time updateTime
            FROM lc_connector ORDER BY connector_name
            """);
    }

    public Map<String, Object> save(Map<String, Object> input)
    {
        String code = code(input.get("connectorCode"));
        String name = required(input.get("connectorName"), 128);
        String type = required(input.get("connectorType"), 32).toUpperCase();
        if (!List.of("HTTP", "SPRING_BEAN", "IOTDB_READ", "SENSOR_DIAGNOSIS").contains(type))
            throw new IllegalArgumentException("连接器类型不受支持");
        String baseUrl = text(input.get("baseUrl"));
        if ("HTTP".equals(type)) outboundTargetValidator.requirePublicHttps(baseUrl);
        int timeout = clamp(number(input.get("timeoutMs"), 5000), 100, 120000);
        int retry = clamp(number(input.get("retryCount"), 0), 0, 1);
        String status = "ENABLED".equals(input.get("status")) ? "ENABLED" : "DISABLED";
        if (input.containsKey("config") && input.get("config") != null)
            throw new IllegalArgumentException("连接器配置不得携带令牌、密码或任意配置；凭据必须使用独立密钥系统引用");
        String authRef = text(input.get("authRef"));
        if (authRef != null && !authRef.isBlank() && !authRef.matches("[A-Za-z][A-Za-z0-9._/-]{2,127}"))
            throw new IllegalArgumentException("连接器凭据引用不合法");
        String username = SecurityUtils.getUsername();
        jdbc.update("""
            INSERT INTO lc_connector(connector_code,connector_name,connector_type,base_url,allowed_paths,timeout_ms,
              retry_count,auth_ref,config_json,status,create_by,create_time,update_by,update_time)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,NOW(),?,NOW())
            ON DUPLICATE KEY UPDATE connector_name=VALUES(connector_name),connector_type=VALUES(connector_type),
              base_url=VALUES(base_url),allowed_paths=VALUES(allowed_paths),timeout_ms=VALUES(timeout_ms),
              retry_count=VALUES(retry_count),auth_ref=VALUES(auth_ref),config_json=VALUES(config_json),
              status=VALUES(status),update_by=VALUES(update_by),update_time=NOW()
            """, code, name, type, baseUrl, text(input.get("allowedPaths")), timeout, retry,
            authRef, null,
            status, username, username);
        return publicView(code);
    }

    public Map<String, Object> publicView(String code)
    {
        Map<String, Object> connector = getForExecution(code);
        connector.remove("authRef");
        connector.remove("configJson");
        return connector;
    }

    Map<String, Object> getForExecution(String code)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT connector_code connectorCode,connector_name connectorName,connector_type connectorType,
              base_url baseUrl,allowed_paths allowedPaths,timeout_ms timeoutMs,retry_count retryCount,
              auth_ref authRef,config_json configJson,status FROM lc_connector WHERE connector_code=?
            """, code);
        if (rows.isEmpty()) throw new IllegalArgumentException("连接器不存在");
        return new LinkedHashMap<>(rows.get(0));
    }

    public Map<String, Object> test(String code)
    {
        Map<String, Object> connector = publicView(code);
        return Map.of("reachable", "ENABLED".equals(connector.get("status")), "connectorCode", code,
            "message", "连接器配置有效；真实业务请求仅允许由已发布动作执行");
    }

    private String code(Object value) { String text = required(value, 64); if (!text.matches("[A-Za-z][A-Za-z0-9_.-]{1,63}")) throw new IllegalArgumentException("连接器编码不合法"); return text; }
    private String required(Object value, int max) { String text = text(value); if (text == null || text.isBlank() || text.length() > max) throw new IllegalArgumentException("连接器必填字段无效"); return text.trim(); }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private int number(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ex) { return fallback; } }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
