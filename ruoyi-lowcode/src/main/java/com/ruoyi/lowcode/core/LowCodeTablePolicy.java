package com.ruoyi.lowcode.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Central table boundary applied during inspection, validation, publication and runtime access. */
@Component
public class LowCodeTablePolicy
{
    private static final Set<String> DENIED_PREFIXES = Set.of(
            "sys_", "qrtz_", "gen_", "flyway_", "lc_", "act_", "oauth_", "audit_");
    private static final Set<String> DENIED_NAMES = Set.of(
            "user", "users", "role", "roles", "permission", "permissions", "credential", "credentials", "config");
    private final JdbcTemplate jdbc;

    public LowCodeTablePolicy(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireAllowed(String table)
    {
        if (isHardDenied(table)) throw new IllegalArgumentException("低代码禁止访问系统核心表: " + table);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lc_resource_allowlist WHERE table_name=? AND enabled=1", Integer.class, table);
        if (count == null || count != 1) throw new IllegalArgumentException("数据表未登记到低代码资源白名单: " + table);
    }

    public boolean isAllowed(String table)
    {
        try { requireAllowed(table); return true; }
        catch (RuntimeException ignored) { return false; }
    }

    public boolean isHardDenied(String table)
    {
        if (table == null) return true;
        String normalized = table.toLowerCase(Locale.ROOT);
        return DENIED_NAMES.contains(normalized) || DENIED_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    public List<Map<String, Object>> list()
    {
        return jdbc.queryForList("SELECT id,table_name tableName,description,enabled,create_by createBy,create_time createTime FROM lc_resource_allowlist ORDER BY table_name");
    }

    public void register(String table, String description, String username)
    {
        if (table == null || !table.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || isHardDenied(table))
            throw new IllegalArgumentException("表名不合法或属于系统保留表");
        jdbc.update("INSERT INTO lc_resource_allowlist(table_name,description,enabled,create_by,create_time) VALUES(?,?,1,?,NOW()) ON DUPLICATE KEY UPDATE description=VALUES(description),enabled=1",
                table, description, username);
    }

    public void disable(Long id) { jdbc.update("UPDATE lc_resource_allowlist SET enabled=0 WHERE id=?", id); }
}
