package com.ruoyi.generator.lowcode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class LowCodeProjectService
{
    private final JdbcTemplate jdbc;
    private final LowCodeMetadataValidator validator;

    public LowCodeProjectService(JdbcTemplate jdbc, LowCodeMetadataValidator validator)
    {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    public List<Map<String, Object>> list()
    {
        return jdbc.queryForList("""
            SELECT p.id,p.app_code appCode,p.project_name projectName,p.description,p.status,
              p.draft_version_id draftVersionId,p.active_version_id activeVersionId,
              d.version_no draftVersionNo,a.version_no activeVersionNo,a.checksum activeChecksum,
              p.create_by createBy,p.create_time createTime,p.update_time updateTime
            FROM lc_project p
            LEFT JOIN lc_version d ON d.id=p.draft_version_id
            LEFT JOIN lc_version a ON a.id=p.active_version_id
            ORDER BY p.update_time DESC
            """);
    }

    public Map<String, Object> get(Long id)
    {
        Map<String, Object> project = requireProject(id);
        Long draftId = number(project.get("draftVersionId"));
        if (draftId != null) project.put("draft", requireVersion(draftId));
        Long activeId = number(project.get("activeVersionId"));
        if (activeId != null) project.put("active", requireVersion(activeId));
        project.put("versions", jdbc.queryForList("""
            SELECT id,version_no versionNo,version_state versionState,checksum,base_version_id baseVersionId,
              create_by createBy,create_time createTime,publish_by publishBy,publish_time publishTime
            FROM lc_version WHERE project_id=? ORDER BY version_no DESC
            """, id));
        return project;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request)
    {
        String appCode = requiredCode(request.get("appCode"), "appCode");
        String name = requiredText(request.get("projectName"), "projectName", 128);
        String preset = String.valueOf(request.getOrDefault("preset", "generic-crud"));
        if (!preset.equals("generic-crud") && !preset.equals("sensor-diagnosis"))
        {
            throw new IllegalArgumentException("不支持的低代码预置: " + preset);
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM lc_project WHERE app_code=?", Integer.class, appCode) > 0)
        {
            throw new IllegalArgumentException("应用编码已存在: " + appCode);
        }
        String metadata = request.get("metadata") == null ? template(preset) : canonical(request.get("metadata"));
        if (metadata.length() > 1024 * 1024)
        {
            throw new IllegalArgumentException("项目元数据不能超过 1MB");
        }
        String username = SecurityUtils.getUsername();
        LocalDateTime now = LocalDateTime.now();
        GeneratedKeyHolder projectKey = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO lc_project(app_code,project_name,description,status,create_by,create_time,update_by,update_time)
                VALUES(?,?,?,'DRAFT',?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, appCode); ps.setString(2, name); ps.setString(3, text(request.get("description")));
            ps.setString(4, username); ps.setObject(5, now); ps.setString(6, username); ps.setObject(7, now);
            return ps;
        }, projectKey);
        Long projectId = projectKey.getKey().longValue();
        Long versionId = insertVersion(projectId, 1, "DRAFT", metadata, null, username);
        jdbc.update("UPDATE lc_project SET draft_version_id=? WHERE id=?", versionId, projectId);
        return get(projectId);
    }

    @Transactional
    public Map<String, Object> saveDraft(Long id, Object metadata)
    {
        Map<String, Object> project = requireProject(id);
        Long draftId = number(project.get("draftVersionId"));
        if (draftId == null) throw new IllegalArgumentException("项目没有可编辑草稿");
        String json = canonical(metadata);
        if (json.length() > 1024 * 1024)
        {
            throw new IllegalArgumentException("项目元数据不能超过 1MB");
        }
        String username = SecurityUtils.getUsername();
        jdbc.update("""
            UPDATE lc_version SET metadata_json=?,checksum=?,validation_json=NULL,create_by=?,create_time=NOW()
            WHERE id=? AND version_state='DRAFT'
            """, json, checksum(json), username, draftId);
        jdbc.update("UPDATE lc_project SET status='DRAFT',update_by=?,update_time=NOW() WHERE id=?", username, id);
        return get(id);
    }

    public Map<String, Object> validate(Long id)
    {
        Map<String, Object> project = requireProject(id);
        Long versionId = number(project.get("draftVersionId"));
        Map<String, Object> version = requireVersion(versionId);
        Map<String, Object> result = validator.validate(String.valueOf(version.get("metadataJson")));
        jdbc.update("UPDATE lc_version SET validation_json=? WHERE id=?", JSON.toJSONString(result), versionId);
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("versionId", versionId);
        response.put("checksum", version.get("checksum"));
        return response;
    }

    public Map<String, Object> diff(Long id)
    {
        Map<String, Object> project = requireProject(id);
        Map<String, Object> draft = requireVersion(number(project.get("draftVersionId")));
        Long activeId = number(project.get("activeVersionId"));
        Object activeMetadata = activeId == null ? null : JSON.parse(String.valueOf(requireVersion(activeId).get("metadataJson")));
        return Map.of("changed", activeId == null || !draft.get("checksum").equals(requireVersion(activeId).get("checksum")),
            "activeVersionId", activeId == null ? "" : activeId, "draftVersionId", draft.get("id"),
            "before", activeMetadata == null ? Map.of() : activeMetadata,
            "after", JSON.parse(String.valueOf(draft.get("metadataJson"))));
    }

    @Transactional
    public Map<String, Object> publish(Long id)
    {
        Map<String, Object> project = requireProject(id);
        Long draftId = number(project.get("draftVersionId"));
        Map<String, Object> draft = requireVersion(draftId);
        Map<String, Object> validation = validator.validate(String.valueOf(draft.get("metadataJson")));
        if (!Boolean.TRUE.equals(validation.get("valid"))) throw new IllegalArgumentException("发布校验未通过: " + JSON.toJSONString(validation.get("errors")));
        Long previous = number(project.get("activeVersionId"));
        String username = SecurityUtils.getUsername();
        jdbc.update("""
            UPDATE lc_version SET version_state='PUBLISHED',validation_json=?,publish_by=?,publish_time=NOW()
            WHERE id=? AND version_state='DRAFT'
            """, JSON.toJSONString(validation), username, draftId);
        int nextNo = ((Number) draft.get("versionNo")).intValue() + 1;
        Long nextDraft = insertVersion(id, nextNo, "DRAFT", String.valueOf(draft.get("metadataJson")), draftId, username);
        jdbc.update("""
            UPDATE lc_project SET active_version_id=?,draft_version_id=?,status='PUBLISHED',update_by=?,update_time=NOW() WHERE id=?
            """, draftId, nextDraft, username, id);
        audit(id, previous, draftId, "PUBLISH", validation, username);
        return get(id);
    }

    @Transactional
    public Map<String, Object> rollback(Long id, Long targetVersionId)
    {
        Map<String, Object> project = requireProject(id);
        Map<String, Object> target = requireVersion(targetVersionId);
        if (!id.equals(number(target.get("projectId"))) || !"PUBLISHED".equals(target.get("versionState")))
            throw new IllegalArgumentException("只能回滚到本项目已发布版本");
        Long previous = number(project.get("activeVersionId"));
        String username = SecurityUtils.getUsername();
        jdbc.update("UPDATE lc_project SET active_version_id=?,status='PUBLISHED',update_by=?,update_time=NOW() WHERE id=?",
            targetVersionId, username, id);
        audit(id, previous, targetVersionId, "ROLLBACK", Map.of("targetChecksum", target.get("checksum")), username);
        return get(id);
    }

    public Map<String, Object> published(String appCode)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT p.id projectId,p.app_code appCode,p.project_name projectName,v.id versionId,v.version_no versionNo,
              v.metadata_json metadataJson,v.checksum
            FROM lc_project p JOIN lc_version v ON v.id=p.active_version_id WHERE p.app_code=? AND v.version_state='PUBLISHED'
            """, appCode);
        if (rows.isEmpty()) throw new IllegalArgumentException("应用不存在或尚未发布");
        return rows.get(0);
    }

    private Map<String, Object> requireProject(Long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id,app_code appCode,project_name projectName,description,status,draft_version_id draftVersionId,
              active_version_id activeVersionId,create_by createBy,create_time createTime,update_by updateBy,update_time updateTime
            FROM lc_project WHERE id=?
            """, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("低代码项目不存在");
        return new LinkedHashMap<>(rows.get(0));
    }

    private Map<String, Object> requireVersion(Long id)
    {
        if (id == null) throw new IllegalArgumentException("版本不存在");
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id,project_id projectId,version_no versionNo,version_state versionState,metadata_json metadataJson,
              checksum,base_version_id baseVersionId,validation_json validationJson,create_by createBy,create_time createTime,
              publish_by publishBy,publish_time publishTime FROM lc_version WHERE id=?
            """, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("低代码版本不存在");
        return new LinkedHashMap<>(rows.get(0));
    }

    private Long insertVersion(Long projectId, int no, String state, String metadata, Long base, String username)
    {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO lc_version(project_id,version_no,version_state,metadata_json,checksum,base_version_id,create_by,create_time)
                VALUES(?,?,?,?,?,?,?,NOW())
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId); ps.setInt(2, no); ps.setString(3, state); ps.setString(4, metadata);
            ps.setString(5, checksum(metadata)); ps.setObject(6, base); ps.setString(7, username);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private void audit(Long projectId, Long from, Long to, String operation, Object detail, String username)
    {
        jdbc.update("""
            INSERT INTO lc_publish_audit(project_id,from_version_id,to_version_id,operation,operator,detail_json,create_time)
            VALUES(?,?,?,?,?,?,NOW())
            """, projectId, from, to, operation, username, JSON.toJSONString(detail));
    }

    private String template(String code)
    {
        List<String> rows = jdbc.query("SELECT metadata_json FROM lc_template WHERE template_code=?", (rs, n) -> rs.getString(1), code);
        if (rows.isEmpty()) throw new IllegalArgumentException("低代码预置不存在: " + code);
        return canonical(JSON.parse(rows.get(0)));
    }

    private String canonical(Object metadata)
    {
        Object parsed = metadata instanceof String text ? JSON.parse(text) : metadata;
        return JSON.toJSONString(parsed, JSONWriter.Feature.MapSortField);
    }

    private String checksum(String value)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private String requiredCode(Object value, String field)
    {
        String code = requiredText(value, field, 64);
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{1,63}")) throw new IllegalArgumentException(field + " 格式错误");
        return code;
    }

    private String requiredText(Object value, String field, int max)
    {
        String text = text(value);
        if (text == null || text.isBlank() || text.length() > max) throw new IllegalArgumentException(field + " 必填且长度不能超过 " + max);
        return text.trim();
    }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private Long number(Object value) { return value instanceof Number n ? n.longValue() : value == null ? null : Long.valueOf(String.valueOf(value)); }
}
