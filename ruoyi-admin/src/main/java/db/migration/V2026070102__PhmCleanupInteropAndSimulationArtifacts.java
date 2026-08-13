package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026070102__PhmCleanupInteropAndSimulationArtifacts extends BaseJavaMigration
{
    @Override
    public boolean canExecuteInTransaction()
    {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_data_cleanup_audit (
              id BIGINT NOT NULL,
              policy_id BIGINT NULL,
              signal_type VARCHAR(64) NULL,
              dry_run TINYINT(1) NOT NULL DEFAULT 1,
              cutoff_time DATETIME NULL,
              stability_window_hours INT NULL,
              max_variation_rate VARCHAR(32) NULL,
              preserve_alarm_window_hours INT NULL,
              candidate_count BIGINT NOT NULL DEFAULT 0,
              deleted_count BIGINT NOT NULL DEFAULT 0,
              status VARCHAR(32) NOT NULL,
              message VARCHAR(1000) NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY(id),
              KEY idx_phm_cleanup_policy_time (policy_id, create_time),
              KEY idx_phm_cleanup_signal_time (signal_type, create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM stable raw data cleanup audit'
            """);
        addColumn(connection, "phm_simulation_layout", "attachment_id",
            "ALTER TABLE phm_simulation_layout ADD COLUMN attachment_id BIGINT NULL AFTER file_url");
        addColumn(connection, "phm_simulation_layout", "version_no",
            "ALTER TABLE phm_simulation_layout ADD COLUMN version_no VARCHAR(64) NULL AFTER attachment_id");
        addIndex(connection, "phm_attachment", "idx_phm_attachment_biz",
            "ALTER TABLE phm_attachment ADD INDEX idx_phm_attachment_biz (biz_type, biz_id, create_time)");
    }

    private void addColumn(Connection connection, String tableName, String columnName, String sql) throws SQLException
    {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, columnName))
        {
            if (!rs.next())
            {
                execute(connection, sql);
            }
        }
    }

    private void addIndex(Connection connection, String tableName, String indexName, String sql) throws SQLException
    {
        try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, tableName, false, false))
        {
            while (rs.next())
            {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME")))
                {
                    return;
                }
            }
        }
        execute(connection, sql);
    }

    private void execute(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }
}
