package com.ruoyi.generator.lowcode;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.lowcode.LowCodeActionHandler;

/** Performs structural and database-drift checks before a version can be published. */
@Component
public class LowCodeMetadataValidator
{
    private static final Set<String> FIELD_TYPES = Set.of("text", "textarea", "number", "switch", "date",
        "datetime", "dict", "entity", "remote", "file", "image", "json", "computed", "long", "decimal");
    private static final Set<String> EVENTS = Set.of("FORM_CHANGE", "BEFORE_SAVE", "AFTER_SAVE", "MANUAL");
    private final DataSource dataSource;
    private final JdbcTemplate systemJdbc;
    private final Set<String> handlerCodes;
    private final LowCodeTablePolicy tablePolicy;

    public LowCodeMetadataValidator(@Qualifier("lowCodeBusinessDataSource") DataSource dataSource,
        @Qualifier("jdbcTemplate") JdbcTemplate systemJdbc,
        List<LowCodeActionHandler> handlers, LowCodeTablePolicy tablePolicy)
    {
        this.dataSource = dataSource;
        this.systemJdbc = systemJdbc;
        this.handlerCodes = handlers.stream().map(LowCodeActionHandler::code).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.tablePolicy = tablePolicy;
    }

    public Map<String, Object> validate(String metadataJson)
    {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        JSONObject root;
        try { root = JSON.parseObject(metadataJson); }
        catch (Exception ex)
        {
            errors.add(issue("INVALID_JSON", "元数据不是合法 JSON", "$"));
            return result(errors, warnings);
        }
        if (root.getIntValue("schemaVersion") != 2) errors.add(issue("SCHEMA_VERSION", "schemaVersion 必须为 2", "schemaVersion"));
        String appType = root.getString("appType");
        if (appType == null || appType.isBlank()) appType = "DATA_APP";
        JSONObject model = root.getJSONObject("model");
        if ("SENSOR_DIAGNOSIS_PIPELINE".equals(appType))
        {
            validatePipeline(root.getJSONObject("pipeline"), errors, warnings);
        }
        else
        {
            if (model == null) errors.add(issue("MODEL_REQUIRED", "缺少数据模型", "model"));
            else validateModel(model, errors, warnings);
        }
        validatePermissions(root.getJSONObject("permissions"), model, errors);
        validateRules(root.getJSONArray("rules"), model, errors);
        validateActions(root.getJSONArray("actions"), errors);
        return result(errors, warnings);
    }

    private void validatePipeline(JSONObject pipeline, List<Map<String, Object>> errors,
        List<Map<String, Object>> warnings)
    {
        if (pipeline == null)
        {
            errors.add(issue("PIPELINE_REQUIRED", "缺少工业诊断管道配置", "pipeline"));
            return;
        }
        com.alibaba.fastjson2.JSONArray bindings = pipeline.getJSONArray("bindings");
        if (bindings == null || bindings.isEmpty())
        {
            errors.add(issue("PIPELINE_BINDINGS_REQUIRED", "至少配置一个测点绑定", "pipeline.bindings"));
        }
        else if (bindings.size() > 8)
        {
            errors.add(issue("PIPELINE_BINDINGS_LIMIT", "单条管道最多配置 8 个测点", "pipeline.bindings"));
        }
        if (bindings != null)
        {
            Set<String> points = new HashSet<>();
            bindings.forEach(raw -> {
                JSONObject binding = (JSONObject) raw;
                String pointId = binding.getString("pointId");
                if (pointId == null || !pointId.matches("\\d+"))
                    errors.add(issue("PIPELINE_POINT_INVALID", "测点 ID 必须为数字", "pipeline.bindings"));
                if (pointId != null && !points.add(pointId))
                    errors.add(issue("PIPELINE_POINT_DUPLICATE", "同一测点不能重复绑定", "pipeline.bindings"));
                String channelId = binding.getString("acquisitionChannelId");
                if (channelId == null || !channelId.matches("\\d+"))
                    errors.add(issue("PIPELINE_CHANNEL_INVALID", "采集通道 ID 必须为数字", "pipeline.bindings"));
                JSONObject diagnosis = binding.getJSONObject("diagnosis");
                if (diagnosis == null)
                {
                    errors.add(issue("PIPELINE_DIAGNOSIS_REQUIRED", "测点缺少诊断模型配置", "pipeline.bindings"));
                }
                else
                {
                    String type = diagnosis.getString("modelType");
                    if (!Set.of("gear", "bearing").contains(type))
                        errors.add(issue("PIPELINE_MODEL_TYPE_INVALID", "模型类型仅支持 gear 或 bearing", "pipeline.bindings.diagnosis"));
                    if (diagnosis.get("modelReleaseId") == null || diagnosis.getString("modelVersion") == null
                        || diagnosis.getString("modelVersion").isBlank())
                        errors.add(issue("PIPELINE_MODEL_VERSION_REQUIRED", "必须固定具体模型版本", "pipeline.bindings.diagnosis"));
                    int window = diagnosis.getIntValue("windowSize");
                    if (window < 128 || window > 262144)
                        errors.add(issue("PIPELINE_WINDOW_INVALID", "诊断窗口必须在 128 到 262144 之间", "pipeline.bindings.diagnosis.windowSize"));
                }
            });
        }
        JSONObject iotdb = pipeline.getJSONObject("iotdb");
        if (iotdb == null || !databaseIdentifier(iotdb.getString("database"))
            || !databaseIdentifier(iotdb.getString("table")))
            errors.add(issue("PIPELINE_IOTDB_INVALID", "IoTDB 数据库和表名不合法", "pipeline.iotdb"));
        else
        {
            JSONObject mapping = iotdb.getJSONObject("fieldMapping");
            for (String field : List.of("waveform", "sampleRate", "sampleCount", "quality", "sequence"))
                if (mapping == null || !databaseIdentifier(mapping.getString(field)))
                    errors.add(issue("PIPELINE_IOTDB_FIELD_REQUIRED", "IoTDB 字段映射缺少 " + field, "pipeline.iotdb.fieldMapping"));
            int maxAge = iotdb.getIntValue("maxFrameAgeSeconds");
            if (maxAge < 1 || maxAge > 86400)
                errors.add(issue("PIPELINE_FRAME_AGE_INVALID", "振动帧最大年龄必须在 1 秒到 24 小时之间", "pipeline.iotdb.maxFrameAgeSeconds"));
        }
        JSONObject schedule = pipeline.getJSONObject("trigger") == null ? null
            : pipeline.getJSONObject("trigger").getJSONObject("schedule");
        if (schedule != null)
        {
            String cron = schedule.getString("cron");
            if (cron == null || cron.isBlank()) errors.add(issue("PIPELINE_CRON_REQUIRED", "定时诊断必须配置 Cron", "pipeline.trigger.schedule.cron"));
            String zone = schedule.getString("timeZone");
            if (zone == null || zone.isBlank()) warnings.add(issue("PIPELINE_TIMEZONE_DEFAULT", "未配置时区，将使用 Asia/Hong_Kong", "pipeline.trigger.schedule.timeZone"));
        }
    }

    private void validateModel(JSONObject model, List<Map<String, Object>> errors, List<Map<String, Object>> warnings)
    {
        String table = model.getString("table");
        if (!databaseIdentifier(table))
        {
            errors.add(issue("TABLE_INVALID", "表名只能包含字母、数字和下划线", "model.table"));
            return;
        }
        try { tablePolicy.requireAllowed(table); }
        catch (IllegalArgumentException ex) { errors.add(issue("TABLE_NOT_ALLOWED", ex.getMessage(), "model.table")); return; }
        Set<String> declared = new HashSet<>();
        if (model.getJSONArray("fields") == null || model.getJSONArray("fields").isEmpty())
            errors.add(issue("FIELDS_REQUIRED", "至少配置一个字段", "model.fields"));
        else model.getJSONArray("fields").forEach(item -> {
            JSONObject field = (JSONObject) item;
            String name = field.getString("name");
            if (!databaseIdentifier(name)) errors.add(issue("FIELD_INVALID", "字段名不合法", "model.fields." + name));
            if (!declared.add(name)) errors.add(issue("FIELD_DUPLICATE", "字段重复: " + name, "model.fields"));
            if (!FIELD_TYPES.contains(field.getString("type"))) errors.add(issue("TYPE_INVALID", "不支持的字段类型: " + field.getString("type"), "model.fields." + name));
        });
        String primaryKey = model.getString("primaryKey");
        if (!declared.contains(primaryKey)) errors.add(issue("PK_MISSING", "主键必须是已声明字段", "model.primaryKey"));
        inspectDatabase(table, declared, errors, warnings);
    }

    private void inspectDatabase(String table, Set<String> declared, List<Map<String, Object>> errors, List<Map<String, Object>> warnings)
    {
        try (Connection connection = dataSource.getConnection())
        {
            DatabaseMetaData metadata = connection.getMetaData();
            boolean tableExists;
            try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, table, new String[] {"TABLE"})) { tableExists = tables.next(); }
            if (!tableExists)
            {
                warnings.add(issue("TABLE_NOT_CREATED", "业务表尚未创建；发布前需应用安全 DDL", "model.table"));
                return;
            }
            Set<String> actual = new HashSet<>();
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, table, null))
            { while (columns.next()) actual.add(columns.getString("COLUMN_NAME")); }
            declared.stream().filter(field -> !actual.contains(field)).forEach(field ->
                errors.add(issue("DATABASE_DRIFT", "数据库缺少字段: " + field, "model.fields." + field)));
        }
        catch (Exception ex)
        {
            errors.add(issue("DATABASE_INSPECT_FAILED", "无法检查数据库结构: " + ex.getMessage(), "model"));
        }
    }

    private void validateRules(com.alibaba.fastjson2.JSONArray rules, JSONObject model, List<Map<String, Object>> errors)
    {
        if (rules == null) return;
        Set<String> codes = new HashSet<>();
        Set<String> fields = new HashSet<>();
        if (model != null && model.getJSONArray("fields") != null)
            model.getJSONArray("fields").forEach(item -> fields.add(((JSONObject) item).getString("name")));
        rules.forEach(item -> {
            JSONObject rule = (JSONObject) item;
            String code = rule.getString("code");
            if (!code(code) || !codes.add(code)) errors.add(issue("RULE_CODE_INVALID", "规则编码为空、非法或重复", "rules"));
            if (rule.get("condition") == null) errors.add(issue("RULE_CONDITION_REQUIRED", "规则缺少 condition", "rules." + code));
            if ("COMPUTE".equals(rule.getString("effect")) && !fields.contains(rule.getString("target")))
                errors.add(issue("RULE_TARGET_INVALID", "派生规则目标必须是已声明字段", "rules." + code + ".target"));
        });
    }

    private void validateActions(com.alibaba.fastjson2.JSONArray actions, List<Map<String, Object>> errors)
    {
        if (actions == null) return;
        Set<String> codes = new HashSet<>();
        actions.forEach(item -> {
            JSONObject action = (JSONObject) item;
            String code = action.getString("code");
            if (!code(code) || !codes.add(code)) errors.add(issue("ACTION_CODE_INVALID", "动作编码为空、非法或重复", "actions"));
            if (!EVENTS.contains(action.getString("event"))) errors.add(issue("ACTION_EVENT_INVALID", "动作事件不受支持", "actions." + code));
            String handler = action.getString("handler");
            if (!code(handler) || !handlerCodes.contains(handler))
                errors.add(issue("ACTION_HANDLER_INVALID", "动作必须引用已注册处理器: " + handler, "actions." + code));
            if ("connector.http".equals(handler)) validateHttpConnector(action, code, errors);
        });
    }

    private void validateHttpConnector(JSONObject action, String actionCode, List<Map<String, Object>> errors)
    {
        String connectorCode = action.getString("connectorCode");
        String path = action.getString("path");
        if (!code(connectorCode) || path == null || !path.startsWith("/") || path.contains(".."))
        {
            errors.add(issue("CONNECTOR_REFERENCE_INVALID", "HTTP动作必须配置连接器编码和安全路径", "actions." + actionCode));
            return;
        }
        try (Connection connection = systemJdbc.getDataSource().getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                 "SELECT allowed_paths FROM lc_connector WHERE connector_code=? AND connector_type='HTTP' AND status='ENABLED'"))
        {
            statement.setString(1, connectorCode);
            try (ResultSet result = statement.executeQuery())
            {
                if (!result.next() || java.util.Arrays.stream(String.valueOf(result.getString(1)).split(","))
                    .map(String::trim).noneMatch(path::equals))
                    errors.add(issue("CONNECTOR_NOT_READY", "HTTP连接器未启用或路径不在白名单", "actions." + actionCode));
            }
        }
        catch (Exception ex) { errors.add(issue("CONNECTOR_CHECK_FAILED", "无法校验连接器: " + ex.getMessage(), "actions." + actionCode)); }
    }

    private void validatePermissions(JSONObject permissions, JSONObject model, List<Map<String, Object>> errors)
    {
        if (permissions == null) return;
        String scope = permissions.getString("dataScope");
        if (!Set.of("NONE", "DEPT", "DEPT_AND_CHILD", "SELF").contains(scope))
        {
            errors.add(issue("DATA_SCOPE_INVALID", "数据范围不受支持", "permissions.dataScope"));
            return;
        }
        Set<String> fields = new HashSet<>();
        if (model != null && model.getJSONArray("fields") != null)
            model.getJSONArray("fields").forEach(item -> fields.add(((JSONObject) item).getString("name")));
        if (("DEPT".equals(scope) || "DEPT_AND_CHILD".equals(scope)) && !fields.contains(permissions.getString("deptField")))
            errors.add(issue("DEPT_FIELD_REQUIRED", "部门数据范围必须映射已声明字段", "permissions.deptField"));
        if ("SELF".equals(scope) && !fields.contains(permissions.getString("userField")))
            errors.add(issue("USER_FIELD_REQUIRED", "本人数据范围必须映射已声明字段", "permissions.userField"));
    }

    private boolean databaseIdentifier(String value) { return value != null && value.matches("[A-Za-z][A-Za-z0-9_]{0,63}"); }
    private boolean code(String value) { return value != null && value.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"); }
    private Map<String, Object> issue(String code, String message, String path) { return Map.of("code", code, "message", message, "path", path); }
    private Map<String, Object> result(List<Map<String, Object>> errors, List<Map<String, Object>> warnings)
    { return Map.of("valid", errors.isEmpty(), "errors", errors, "warnings", warnings); }
}
