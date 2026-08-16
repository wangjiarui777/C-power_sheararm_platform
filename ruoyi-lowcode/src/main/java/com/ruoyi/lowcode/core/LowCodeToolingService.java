package com.ruoyi.lowcode.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

@Service
public class LowCodeToolingService
{
    private final JdbcTemplate businessJdbc;
    private final LowCodeProjectService projects;
    private final LowCodeRuntimeService runtime;

    public LowCodeToolingService(
        @org.springframework.beans.factory.annotation.Qualifier("lowCodeBusinessJdbcTemplate") JdbcTemplate businessJdbc,
        LowCodeProjectService projects, LowCodeRuntimeService runtime)
    { this.businessJdbc = businessJdbc; this.projects = projects; this.runtime = runtime; }

    public List<Map<String, Object>> inspect() { return runtime.inspectTables(); }

    public Map<String, Object> ddlPreview(Object rawMetadata)
    {
        JSONObject root = rawMetadata instanceof String text ? JSON.parseObject(text) : JSON.parseObject(JSON.toJSONString(rawMetadata));
        JSONObject model = root.getJSONObject("model");
        String table = model.getString("table");
        if (!identifier(table)) throw new IllegalArgumentException("表名不合法");
        Map<String, String> existing = columns(table);
        List<String> statements = new ArrayList<>();
        if (existing.isEmpty())
        {
            List<String> definitions = new ArrayList<>();
            for (Object item : model.getJSONArray("fields"))
            {
                JSONObject field = (JSONObject) item;
                definitions.add("  `" + safe(field.getString("name")) + "` " + sqlType(field) + nullable(field));
            }
            definitions.add("  PRIMARY KEY (`" + safe(model.getString("primaryKey")) + "`)");
            statements.add("CREATE TABLE `" + table + "` (\n" + String.join(",\n", definitions)
                + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        else
        {
            for (Object item : model.getJSONArray("fields"))
            {
                JSONObject field = (JSONObject) item;
                String name = safe(field.getString("name"));
                if (!existing.containsKey(name))
                {
                    if (Boolean.TRUE.equals(field.getBoolean("required")) && field.get("defaultValue") == null)
                        throw new IllegalArgumentException("新增必填字段必须配置默认值: " + name);
                    String defaultClause = field.get("defaultValue") == null ? "" : " DEFAULT '"
                        + String.valueOf(field.get("defaultValue")).replace("'", "''") + "'";
                    statements.add("ALTER TABLE `" + table + "` ADD COLUMN `" + name + "` " + sqlType(field) + nullable(field) + defaultClause);
                }
            }
        }
        return Map.of("safe", true, "statements", statements,
            "notice", "仅生成新表或新增字段语句；不会删除、重命名、收窄字段或修改主键");
    }

    public void export(Long projectId, OutputStream output)
    {
        Map<String, Object> project = projects.get(projectId);
        Object active = project.get("active");
        if (!(active instanceof Map<?, ?> version)) throw new IllegalArgumentException("项目尚未发布，不能导出");
        String metadata = String.valueOf(version.get("metadataJson"));
        String manifest = JSON.toJSONString(Map.of("appCode", project.get("appCode"), "projectName", project.get("projectName"),
            "versionNo", version.get("versionNo"), "checksum", version.get("checksum"), "schemaVersion", 2),
            JSONWriter.Feature.PrettyFormat);
        String readme = """
            # RuoYi Low-Code Export

            此压缩包是已发布元数据的不可变快照。manifest.json 中包含版本与 SHA-256 校验和。
            metadata.json 是权威快照；复杂动作通过 LowCodeActionHandler 扩展，不生成任意脚本。
            导出内容仅包含已发布元数据，不生成任意源码或脚本。
            """;
        try (ZipOutputStream zip = new ZipOutputStream(output))
        {
            add(zip, "manifest.json", manifest); add(zip, "metadata.json", JSON.toJSONString(JSON.parse(metadata), JSONWriter.Feature.PrettyFormat));
            add(zip, "README.md", readme); zip.finish();
        }
        catch (Exception ex) { throw new IllegalStateException("导出低代码版本失败", ex); }
    }

    private Map<String, String> columns(String table)
    {
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection connection = businessJdbc.getDataSource().getConnection())
        {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, table, null))
            { while (columns.next()) result.put(columns.getString("COLUMN_NAME"), columns.getString("TYPE_NAME")); }
            return result;
        }
        catch (Exception ex) { throw new IllegalStateException("数据库结构检查失败", ex); }
    }

    private String sqlType(JSONObject field)
    {
        String type = field.getString("type");
        return switch (type)
        {
            case "long", "entity" -> "BIGINT";
            case "number", "decimal" -> "DECIMAL(18,4)";
            case "switch" -> "TINYINT(1)";
            case "date" -> "DATE";
            case "datetime" -> "DATETIME";
            case "textarea", "json" -> "LONGTEXT";
            default -> "VARCHAR(" + Math.max(1, Math.min(field.getIntValue("length", 255), 4000)) + ")";
        };
    }
    private String nullable(JSONObject field) { return Boolean.TRUE.equals(field.getBoolean("required")) ? " NOT NULL" : " NULL"; }
    private String mapType(String html, String javaType) { if (Set.of("select", "radio", "checkbox").contains(html)) return "dict"; if (html.contains("Upload")) return html.startsWith("image") ? "image" : "file"; if ("datetime".equals(html)) return "datetime"; if ("textarea".equals(html) || "editor".equals(html)) return "textarea"; if (Set.of("Long", "Integer", "Double", "BigDecimal").contains(javaType)) return "number"; if ("Boolean".equals(javaType)) return "switch"; return "text"; }
    private boolean identifier(String value) { return value != null && value.matches("[A-Za-z][A-Za-z0-9_]{0,63}"); }
    private String safe(String value) { if (!identifier(value)) throw new IllegalArgumentException("字段名不合法: " + value); return value; }
    private void add(ZipOutputStream zip, String name, String content) throws IOException
    {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (data.length > 10 * 1024 * 1024) throw new IllegalArgumentException("低代码导出单文件超过 10MB 上限");
        ZipEntry entry = new ZipEntry(name);
        entry.setSize(data.length);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
