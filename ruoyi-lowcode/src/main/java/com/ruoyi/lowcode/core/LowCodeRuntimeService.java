package com.ruoyi.lowcode.core;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.lowcode.LowCodeActionContext;
import com.ruoyi.common.lowcode.LowCodeActionHandler;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class LowCodeRuntimeService
{
    private static final AtomicLong LONG_IDS = new AtomicLong(System.currentTimeMillis() << 12);
    private final LowCodeProjectService projects;
    private final JdbcTemplate systemJdbc;
    private final JdbcTemplate businessJdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final LowCodeRuleEngine rules;
    private final Map<String, LowCodeActionHandler> handlers;
    private final LowCodeTablePolicy tablePolicy;

    @Value("${lowcode.runtime.write-enabled:false}")
    private boolean writeEnabled;

    public LowCodeRuntimeService(LowCodeProjectService projects,
        @Qualifier("jdbcTemplate") JdbcTemplate systemJdbc,
        @Qualifier("lowCodeBusinessJdbcTemplate") JdbcTemplate businessJdbc, LowCodeRuleEngine rules,
        List<LowCodeActionHandler> actionHandlers, LowCodeTablePolicy tablePolicy)
    {
        this.projects = projects;
        this.systemJdbc = systemJdbc;
        this.businessJdbc = businessJdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(businessJdbc);
        this.rules = rules;
        this.handlers = actionHandlers.stream().collect(Collectors.toUnmodifiableMap(LowCodeActionHandler::code, item -> item));
        this.tablePolicy = tablePolicy;
    }

    public Map<String, Object> schema(String appCode)
    {
        Map<String, Object> published = projects.published(appCode);
        Object metadata = JSON.parse(String.valueOf(published.get("metadataJson")));
        return Map.of("appCode", appCode, "projectName", published.get("projectName"),
            "versionId", published.get("versionId"), "versionNo", published.get("versionNo"),
            "checksum", published.get("checksum"), "writeEnabled", writeEnabled, "metadata", metadata);
    }

    public Map<String, Object> list(String appCode, Map<String, String> query)
    {
        RuntimeModel runtime = model(appCode);
        int pageNum = clamp(integer(query.get("pageNum"), 1), 1, 1_000_000);
        int pageSize = clamp(integer(query.get("pageSize"), 20), 1, 200);
        String sort = query.getOrDefault("sort", runtime.primaryKey);
        if (!runtime.listFields.contains(sort)) throw new IllegalArgumentException("排序字段未发布");
        String order = "asc".equalsIgnoreCase(query.get("order")) ? "ASC" : "DESC";
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();
        for (String field : runtime.queryFields)
        {
            String value = query.get(field);
            if (value != null && !value.isBlank())
            {
                String operator = runtime.queryOperators.getOrDefault(field, "EQ");
                switch (operator)
                {
                    case "NE" -> predicates.add(quote(field) + " <> :" + field);
                    case "GT" -> predicates.add(quote(field) + " > :" + field);
                    case "GTE" -> predicates.add(quote(field) + " >= :" + field);
                    case "LT" -> predicates.add(quote(field) + " < :" + field);
                    case "LTE" -> predicates.add(quote(field) + " <= :" + field);
                    case "LIKE" -> predicates.add(quote(field) + " LIKE :" + field);
                    default -> predicates.add(quote(field) + " = :" + field);
                }
                params.addValue(field, "LIKE".equals(operator) ? "%" + value + "%" : value);
            }
        }
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        String columns = runtime.listFields.stream().map(this::quote).collect(Collectors.joining(","));
        Long total = namedJdbc.queryForObject("SELECT COUNT(*) FROM " + quote(runtime.table) + where, params, Long.class);
        params.addValue("limit", pageSize).addValue("offset", (pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = namedJdbc.queryForList("SELECT " + columns + " FROM " + quote(runtime.table)
            + where + " ORDER BY " + quote(sort) + " " + order + " LIMIT :limit OFFSET :offset", params);
        return Map.of("rows", rows, "total", total == null ? 0 : total, "pageNum", pageNum, "pageSize", pageSize);
    }

    public Map<String, Object> get(String appCode, Object id)
    {
        RuntimeModel runtime = model(appCode);
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        List<String> predicates = new ArrayList<>(List.of(quote(runtime.primaryKey) + "=:id"));
        List<Map<String, Object>> rows = namedJdbc.queryForList("SELECT " + runtime.allFields.stream().map(this::quote).collect(Collectors.joining(","))
            + " FROM " + quote(runtime.table) + " WHERE " + String.join(" AND ", predicates) + " LIMIT 1", params);
        if (rows.isEmpty()) throw new IllegalArgumentException("记录不存在或无权访问");
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> create(String appCode, Map<String, Object> input)
    {
        requireWrites();
        RuntimeModel runtime = model(appCode);
        Map<String, Object> values = sanitize(input, runtime.insertFields);
        generateValues(runtime, values, true);
        applyRuleValidation(runtime.metadata, values);
        requireAllowed(values, runtime.insertFields, runtime.generatedFields);
        String operationId = java.util.UUID.randomUUID().toString();
        executeEvent(runtime, "BEFORE_SAVE", values, operationId);
        if (values.isEmpty()) throw new IllegalArgumentException("没有允许新增的字段");
        String fields = values.keySet().stream().map(this::quote).collect(Collectors.joining(","));
        String binds = values.keySet().stream().map(field -> ":" + field).collect(Collectors.joining(","));
        namedJdbc.update("INSERT INTO " + quote(runtime.table) + "(" + fields + ") VALUES(" + binds + ")", values);
        executeEvent(runtime, "AFTER_SAVE", values, operationId);
        return values;
    }

    @Transactional
    public Map<String, Object> update(String appCode, Object id, Map<String, Object> input)
    {
        requireWrites();
        RuntimeModel runtime = model(appCode);
        Map<String, Object> existing = get(appCode, id);
        Map<String, Object> values = sanitize(input, runtime.editFields);
        Map<String, Object> validationValues = new LinkedHashMap<>(existing);
        validationValues.putAll(values);
        applyRuleValidation(runtime.metadata, validationValues);
        validationValues.forEach((key, value) -> { if (runtime.editFields.contains(key) && !values.containsKey(key)
            && !java.util.Objects.equals(existing.get(key), value)) values.put(key, value); });
        generateValues(runtime, values, false);
        requireAllowed(values, runtime.editFields, runtime.generatedFields);
        String operationId = java.util.UUID.randomUUID().toString();
        executeEvent(runtime, "BEFORE_SAVE", values, operationId);
        if (values.isEmpty()) throw new IllegalArgumentException("没有允许修改的字段");
        MapSqlParameterSource params = new MapSqlParameterSource(values).addValue("recordId", id);
        List<String> predicates = new ArrayList<>();
        predicates.add(quote(runtime.primaryKey) + "=:recordId");
        String set = values.keySet().stream().map(field -> quote(field) + "=:" + field).collect(Collectors.joining(","));
        int affected = namedJdbc.update("UPDATE " + quote(runtime.table) + " SET " + set + " WHERE " + String.join(" AND ", predicates), params);
        if (affected != 1) throw new IllegalStateException("记录不存在、无权修改或数据已并发变化");
        executeEvent(runtime, "AFTER_SAVE", values, operationId);
        return get(appCode, id);
    }

    @Transactional
    public void delete(String appCode, Object id)
    {
        requireWrites();
        RuntimeModel runtime = model(appCode);
        get(appCode, id);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("recordId", id);
        List<String> predicates = new ArrayList<>();
        predicates.add(quote(runtime.primaryKey) + "=:recordId");
        int affected = namedJdbc.update("DELETE FROM " + quote(runtime.table) + " WHERE " + String.join(" AND ", predicates), params);
        if (affected != 1) throw new IllegalStateException("记录不存在、无权删除或数据已并发变化");
    }

    public Map<String, Object> action(String appCode, String actionCode, Map<String, Object> input, String idempotencyKey)
    {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
            throw new IllegalArgumentException("Idempotency-Key 必填且不能超过128个字符");
        RuntimeModel runtime = model(appCode);
        JSONObject action = runtime.actions.stream().map(JSONObject.class::cast)
            .filter(item -> actionCode.equals(item.getString("code")) && "MANUAL".equals(item.getString("event")))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("动作不存在或不是手动动作"));
        return invoke(runtime, action, input, idempotencyKey);
    }

    public List<Map<String, Object>> inspectTables()
    {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = businessJdbc.getDataSource().getConnection())
        {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"}))
            {
                while (tables.next())
                {
                    String table = tables.getString("TABLE_NAME");
                    if (!tablePolicy.isAllowed(table)) continue;
                    List<Map<String, Object>> columns = new ArrayList<>();
                    try (ResultSet cols = metadata.getColumns(connection.getCatalog(), null, table, null))
                    {
                        while (cols.next()) columns.add(Map.of("name", cols.getString("COLUMN_NAME"),
                            "databaseType", cols.getString("TYPE_NAME"), "nullable", cols.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                    }
                    result.add(Map.of("table", table, "columns", columns));
                }
            }
            return result;
        }
        catch (Exception ex) { throw new IllegalStateException("读取数据库结构失败", ex); }
    }

    private RuntimeModel model(String appCode)
    {
        Map<String, Object> published = projects.published(appCode);
        JSONObject metadata = JSON.parseObject(String.valueOf(published.get("metadataJson")));
        JSONObject model = metadata.getJSONObject("model");
        String table = model.getString("table");
        String primaryKey = model.getString("primaryKey");
        if (!identifier(table) || !identifier(primaryKey)) throw new IllegalStateException("已发布模型标识符不合法");
        tablePolicy.requireAllowed(table);
        Set<String> all = new LinkedHashSet<>(), list = new LinkedHashSet<>(), query = new LinkedHashSet<>(),
            insert = new LinkedHashSet<>(), edit = new LinkedHashSet<>(), generated = new LinkedHashSet<>();
        Map<String, String> queryOperators = new LinkedHashMap<>();
        model.getJSONArray("fields").forEach(item -> {
            JSONObject field = (JSONObject) item;
            String name = field.getString("name");
            if (!identifier(name)) throw new IllegalStateException("已发布字段不合法: " + name);
            all.add(name);
            if (field.getString("generated") != null && !"database".equals(field.getString("generated"))) generated.add(name);
            if (!Boolean.FALSE.equals(field.getBoolean("list"))) list.add(name);
            if (Boolean.TRUE.equals(field.getBoolean("query"))) { query.add(name); queryOperators.put(name, field.getString("queryType") == null ? "EQ" : field.getString("queryType")); }
            if (!Boolean.TRUE.equals(field.getBoolean("readOnly")) && !primaryKey.equals(name)) { insert.add(name); edit.add(name); }
            if (Boolean.FALSE.equals(field.getBoolean("insert"))) insert.remove(name);
            if (Boolean.FALSE.equals(field.getBoolean("edit"))) edit.remove(name);
        });
        list.add(primaryKey);
        return new RuntimeModel(appCode, table, primaryKey, all, list, query, queryOperators, insert, edit, generated, metadata,
            metadata.getJSONArray("actions") == null ? new JSONArray() : metadata.getJSONArray("actions"));
    }

    private Map<String, Object> sanitize(Map<String, Object> input, Set<String> allowed)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> { if (allowed.contains(key)) result.put(key, value); });
        Set<String> rejected = input.keySet().stream().filter(key -> !allowed.contains(key)).collect(Collectors.toSet());
        if (!rejected.isEmpty()) throw new IllegalArgumentException("包含未发布或无权写入字段: " + rejected);
        return result;
    }

    private void requireAllowed(Map<String, Object> values, Set<String> allowed, Set<String> generated)
    {
        Set<String> rejected = values.keySet().stream().filter(key -> !allowed.contains(key) && !generated.contains(key)).collect(Collectors.toSet());
        if (!rejected.isEmpty()) throw new IllegalArgumentException("规则尝试写入未发布字段: " + rejected);
    }

    private void generateValues(RuntimeModel runtime, Map<String, Object> values, boolean insert)
    {
        for (Object item : runtime.metadata.getJSONObject("model").getJSONArray("fields"))
        {
            JSONObject field = (JSONObject) item;
            String name = field.getString("name");
            if (insert && !values.containsKey(name) && field.containsKey("defaultValue")) values.put(name, field.get("defaultValue"));
            String strategy = field.getString("generated");
            if ("long".equals(strategy) && insert && !values.containsKey(name))
            {
                long clock = System.currentTimeMillis() << 12;
                values.put(name, LONG_IDS.updateAndGet(previous -> Math.max(previous + 1, clock)));
            }
            else if ("uuid".equals(strategy) && insert && !values.containsKey(name)) values.put(name, java.util.UUID.randomUUID().toString());
            else if ("now".equals(strategy) || insert && "nowOnCreate".equals(strategy))
                values.put(name, new java.sql.Timestamp(System.currentTimeMillis()));
            else if ("username".equals(strategy)) values.put(name, SecurityUtils.getUsername());
        }
    }

    private void applyRuleValidation(JSONObject metadata, Map<String, Object> values)
    {
        JSONObject model = metadata.getJSONObject("model");
        if (model != null && model.getJSONArray("fields") != null)
        {
            for (Object item : model.getJSONArray("fields"))
            {
                JSONObject field = (JSONObject) item;
                String name = field.getString("name");
                if (Boolean.TRUE.equals(field.getBoolean("required")) && !Boolean.TRUE.equals(field.getBoolean("readOnly"))
                    && (!values.containsKey(name) || values.get(name) == null || String.valueOf(values.get(name)).isBlank()))
                    throw new IllegalArgumentException((field.getString("label") == null ? name : field.getString("label")) + " 为必填项");
            }
        }
        JSONArray configured = metadata.getJSONArray("rules");
        if (configured == null) return;
        for (Object item : configured)
        {
            JSONObject rule = (JSONObject) item;
            if ("VALIDATE".equals(rule.getString("effect")) && rules.matches(rule.get("condition"), values))
                throw new IllegalArgumentException(rule.getString("message"));
            if ("COMPUTE".equals(rule.getString("effect")) && rules.matches(rule.get("condition"), values))
                values.put(rule.getString("target"), rules.evaluate(rule.get("value"), values));
        }
    }

    private void executeEvent(RuntimeModel runtime, String event, Map<String, Object> values, String operationId)
    {
        for (Object item : runtime.actions)
        {
            JSONObject action = (JSONObject) item;
            if (event.equals(action.getString("event")))
                invoke(runtime, action, values, automaticIdempotencyKey(runtime, action, event, operationId));
        }
    }

    private String automaticIdempotencyKey(RuntimeModel runtime, JSONObject action, String event, String operationId)
    {
        try
        {
            String material = runtime.appCode + "\n" + action.getString("code") + "\n" + event + "\n" + operationId;
            return "auto-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) { throw new IllegalStateException("无法生成动作幂等键", ex); }
    }

    private Map<String, Object> invoke(RuntimeModel runtime, JSONObject action, Map<String, Object> input, String idempotencyKey)
    {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new IllegalArgumentException("副作用动作必须提供 Idempotency-Key");
        if (idempotencyKey.length() > 128) throw new IllegalArgumentException("Idempotency-Key 过长");
        int claimed = systemJdbc.update("""
            INSERT IGNORE INTO lc_action_log(app_code,action_code,idempotency_key,status,duration_ms,create_by,create_time)
            VALUES(?,?,?,'RUNNING',0,?,NOW())
            """, runtime.appCode, action.getString("code"), idempotencyKey, SecurityUtils.getUsername());
        if (claimed == 0)
        {
            List<Map<String, Object>> existing = systemJdbc.queryForList("""
                SELECT status,response_json responseJson FROM lc_action_log
                WHERE app_code=? AND action_code=? AND idempotency_key=? LIMIT 1
                """, runtime.appCode, action.getString("code"), idempotencyKey);
            if (!existing.isEmpty() && "SUCCEEDED".equals(existing.get(0).get("status"))
                    && existing.get(0).get("responseJson") != null)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = JSON.parseObject(String.valueOf(existing.get(0).get("responseJson")), Map.class);
                return result == null ? Map.of() : result;
            }
            throw new IllegalStateException("同一幂等键的动作正在执行或此前失败");
        }
        LowCodeActionHandler handler = handlers.get(action.getString("handler"));
        if (handler == null) throw new IllegalArgumentException("动作处理器未注册: " + action.getString("handler"));
        long start = System.currentTimeMillis();
        String username = SecurityUtils.getUsername();
        try
        {
            Map<String, Object> result = handler.execute(input, new LowCodeActionContext(runtime.appCode,
                action.getString("code"), action.getString("event"), username, SecurityUtils.getUserId(), SecurityUtils.getDeptId(),
                idempotencyKey, action));
            Map<String, Object> safeResult = result == null ? Map.of() : result;
            finishAction(runtime.appCode, action.getString("code"), idempotencyKey, "SUCCEEDED", start, safeResult, null);
            return safeResult;
        }
        catch (Exception ex)
        {
            finishAction(runtime.appCode, action.getString("code"), idempotencyKey, "FAILED", start, null, ex.getMessage());
            throw new IllegalArgumentException("动作执行失败: " + ex.getMessage(), ex);
        }
    }

    private void finishAction(String appCode, String actionCode, String key, String status, long start, Object response, String error)
    {
        systemJdbc.update("""
            UPDATE lc_action_log SET status=?,duration_ms=?,response_json=?,error_message=?
            WHERE app_code=? AND action_code=? AND idempotency_key=? AND status='RUNNING'
            """, status, System.currentTimeMillis() - start,
            response == null ? null : JSON.toJSONString(response), error, appCode, actionCode, key);
    }

    private void requireWrites() { if (!writeEnabled) throw new IllegalStateException("动态运行时写入已关闭"); }
    private String quote(String identifier) { if (!identifier(identifier)) throw new IllegalArgumentException("标识符不合法"); return "`" + identifier + "`"; }
    private boolean identifier(String value) { return value != null && value.matches("[A-Za-z][A-Za-z0-9_]{0,63}"); }
    private int integer(String value, int fallback) { try { return value == null ? fallback : Integer.parseInt(value); } catch (Exception ex) { return fallback; } }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private record RuntimeModel(String appCode, String table, String primaryKey, Set<String> allFields, Set<String> listFields,
        Set<String> queryFields, Map<String, String> queryOperators, Set<String> insertFields, Set<String> editFields,
        Set<String> generatedFields, JSONObject metadata, JSONArray actions) {}
}
