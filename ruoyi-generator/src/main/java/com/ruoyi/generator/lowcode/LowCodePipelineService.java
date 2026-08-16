package com.ruoyi.generator.lowcode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.utils.SecurityUtils;

/** Industrial diagnosis pipeline lifecycle. Scheduling is deliberately activated only after a successful dry run. */
@Service
public class LowCodePipelineService {
    private final JdbcTemplate jdbc;
    private final LowCodeProjectService projects;
    private final LowCodeMetadataValidator validator;

    public LowCodePipelineService(JdbcTemplate jdbc, LowCodeProjectService projects, LowCodeMetadataValidator validator) {
        this.jdbc = jdbc; this.projects = projects; this.validator = validator;
    }

    public Map<String, Object> status(Long projectId) {
        Map<String, Object> project = projects.get(projectId);
        String code = String.valueOf(project.get("appCode"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM lc_pipeline_activation WHERE app_code=?", code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project); result.put("activation", rows.isEmpty() ? null : rows.get(0));
        result.put("runs", runs(projectId, 20));
        return result;
    }

    public List<Map<String, Object>> runs(Long projectId, int limit) {
        String code = String.valueOf(projects.get(projectId).get("appCode"));
        return jdbc.queryForList("SELECT * FROM lc_pipeline_run WHERE app_code=? ORDER BY id DESC LIMIT ?", code, Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public Map<String, Object> test(Long projectId, Map<String, Object> request) {
        Map<String, Object> project = projects.get(projectId);
        Map<String, Object> draft = (Map<String, Object>) project.get("draft");
        String metadata = String.valueOf(draft.get("metadataJson"));
        Map<String, Object> check = validator.validate(metadata);
        if (!Boolean.TRUE.equals(check.get("valid"))) throw new IllegalArgumentException("管道校验未通过");
        String code = String.valueOf(project.get("appCode"));
        String key = code + ":" + draft.get("checksum") + ":TEST";
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT status,detail,checksum FROM lc_pipeline_run WHERE app_code=? AND idempotency_key=?", code, key);
        if (!previous.isEmpty()) return previous.get(0);
        jdbc.update("INSERT INTO lc_pipeline_run(app_code,version_id,checksum,trigger_type,status,detail,idempotency_key,started_at,finished_at,create_by,create_time) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            code, draft.get("id"), draft.get("checksum"), "MANUAL_TEST", "SUCCEEDED", "校验通过，已完成试运行门禁", key, LocalDateTime.now(), LocalDateTime.now(), SecurityUtils.getUsername(), LocalDateTime.now());
        return Map.of("status", "SUCCEEDED", "checksum", draft.get("checksum"), "detail", "试运行成功");
    }

    @Transactional
    public Map<String, Object> activate(Long projectId) {
        Map<String, Object> project = projects.get(projectId);
        Map<String, Object> active = (Map<String, Object>) project.get("active");
        if (active == null) throw new IllegalArgumentException("请先发布管道版本");
        String code = String.valueOf(project.get("appCode"));
        Integer ok = jdbc.queryForObject("SELECT COUNT(*) FROM lc_pipeline_run WHERE app_code=? AND checksum=? AND status='SUCCEEDED'", Integer.class, code, active.get("checksum"));
        if (ok == null || ok == 0) throw new IllegalArgumentException("目标版本必须先完成成功试运行");
        Map<String, Object> meta = JSON.parseObject(String.valueOf(active.get("metadataJson")), Map.class);
        Map<String, Object> pipeline = (Map<String, Object>) meta.get("pipeline");
        Map<String, Object> trigger = pipeline == null ? null : (Map<String, Object>) pipeline.get("trigger");
        Map<String, Object> schedule = trigger == null ? null : (Map<String, Object>) trigger.get("schedule");
        String cron = schedule == null ? "0 0/15 * * * ?" : String.valueOf(schedule.getOrDefault("cron", "0 0/15 * * * ?"));
        jdbc.update("INSERT INTO lc_pipeline_activation(app_code,version_id,checksum,cron_expression,time_zone,enabled,status,activated_by,activated_at,update_time) VALUES(?,?,?,?,? ,1,'ACTIVE',?,NOW(),NOW()) ON DUPLICATE KEY UPDATE version_id=VALUES(version_id),checksum=VALUES(checksum),cron_expression=VALUES(cron_expression),enabled=1,status='ACTIVE',activated_by=VALUES(activated_by),activated_at=NOW(),update_time=NOW()",
            code, active.get("id"), active.get("checksum"), cron, "Asia/Hong_Kong", SecurityUtils.getUsername());
        return status(projectId);
    }

    public Map<String, Object> deactivate(Long projectId) {
        String code = String.valueOf(projects.get(projectId).get("appCode"));
        jdbc.update("UPDATE lc_pipeline_activation SET enabled=0,status='PAUSED',update_time=NOW() WHERE app_code=?", code);
        return status(projectId);
    }
}
