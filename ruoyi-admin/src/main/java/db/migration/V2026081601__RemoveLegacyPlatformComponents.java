package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Removes the RuoYi development/operations extensions no longer used by PHM. */
public class V2026081601__RemoveLegacyPlatformComponents extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        if (tableExists(connection, "sys_menu"))
        {
            try (Statement statement = connection.createStatement())
            {
                statement.execute("CREATE TEMPORARY TABLE IF NOT EXISTS tmp_legacy_menu_ids (menu_id BIGINT PRIMARY KEY)");
                statement.execute("""
                    INSERT IGNORE INTO tmp_legacy_menu_ids(menu_id)
                    SELECT menu_id FROM sys_menu
                    WHERE menu_name IN ('定时任务','数据监控','服务监控','缓存监控','缓存列表','代码生成','系统接口','岗位管理','通知公告','表单构建')
                       OR path IN ('job','druid','server','cache','cacheList','gen','swagger','post','notice','build')
                       OR component IN ('monitor/job/index','monitor/druid/index','monitor/server/index','monitor/cache/index',
                                        'monitor/cache/list','tool/gen/index','tool/swagger/index','system/post/index','system/notice/index')
                       OR perms LIKE 'monitor:job:%' OR perms LIKE 'monitor:druid:%' OR perms LIKE 'monitor:server:%'
                       OR perms LIKE 'monitor:cache:%' OR perms LIKE 'tool:gen:%' OR perms LIKE 'tool:build:%'
                       OR perms LIKE 'system:post:%' OR perms LIKE 'system:notice:%'
                    """);
            }

            // Include function buttons and any future descendants before deleting role mappings.
            boolean added;
            do
            {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT IGNORE INTO tmp_legacy_menu_ids(menu_id)
                    SELECT menu_id FROM sys_menu WHERE parent_id IN (SELECT menu_id FROM tmp_legacy_menu_ids)
                    """))
                {
                    added = statement.executeUpdate() > 0;
                }
            }
            while (added);

            try (Statement statement = connection.createStatement())
            {
                if (tableExists(connection, "sys_role_menu"))
                {
                    statement.execute("DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM tmp_legacy_menu_ids)");
                }
                statement.execute("DELETE FROM sys_menu WHERE menu_id IN (SELECT menu_id FROM tmp_legacy_menu_ids)");
                statement.execute("DROP TEMPORARY TABLE IF EXISTS tmp_legacy_menu_ids");
            }
        }

        try (Statement statement = connection.createStatement())
        {
            // These tables are not referenced by the retained account, audit, PHM or low-code services.
            statement.execute("DROP TABLE IF EXISTS sys_job_log");
            statement.execute("DROP TABLE IF EXISTS sys_job");
            statement.execute("DROP TABLE IF EXISTS gen_table_column");
            statement.execute("DROP TABLE IF EXISTS gen_table");
            statement.execute("DROP TABLE IF EXISTS sys_user_post");
            statement.execute("DROP TABLE IF EXISTS sys_post");
            statement.execute("DROP TABLE IF EXISTS sys_notice_read");
            statement.execute("DROP TABLE IF EXISTS sys_notice");
            dropQuartzTables(statement);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?"))
        {
            statement.setString(1, tableName);
            try (var result = statement.executeQuery())
            {
                result.next();
                return result.getInt(1) > 0;
            }
        }
    }

    private void dropQuartzTables(Statement statement) throws Exception
    {
        String[] tables = {
            "QRTZ_FIRED_TRIGGERS", "QRTZ_PAUSED_TRIGGER_GRPS", "QRTZ_SCHEDULER_STATE",
            "QRTZ_LOCKS", "QRTZ_SIMPLE_TRIGGERS", "QRTZ_CRON_TRIGGERS", "QRTZ_SIMPROP_TRIGGERS",
            "QRTZ_BLOB_TRIGGERS", "QRTZ_TRIGGERS", "QRTZ_JOB_DETAILS", "QRTZ_CALENDARS"
        };
        for (String table : tables)
        {
            statement.execute("DROP TABLE IF EXISTS `" + table + "`");
        }
    }
}
