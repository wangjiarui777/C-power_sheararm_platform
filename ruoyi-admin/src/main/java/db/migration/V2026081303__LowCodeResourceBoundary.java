package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Explicit allow-list required before low-code may access any business table. */
public class V2026081303__LowCodeResourceBoundary extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        try (Statement statement = context.getConnection().createStatement())
        {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_resource_allowlist (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  table_name VARCHAR(64) NOT NULL,
                  description VARCHAR(255) NULL,
                  enabled TINYINT(1) NOT NULL DEFAULT 1,
                  create_by VARCHAR(64) NOT NULL,
                  create_time DATETIME NOT NULL,
                  PRIMARY KEY(id), UNIQUE KEY uk_lc_resource_table(table_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码业务资源白名单'
                """);
            statement.execute("""
                UPDATE lc_action_log
                SET idempotency_key = CONCAT('legacy-', id)
                WHERE idempotency_key IS NULL OR idempotency_key = ''
                """);
            statement.execute("ALTER TABLE lc_action_log MODIFY idempotency_key VARCHAR(128) NOT NULL");
        }
    }
}
