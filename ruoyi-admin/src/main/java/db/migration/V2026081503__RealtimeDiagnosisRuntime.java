package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Creates the real-time diagnosis policy and additive task/result fields. */
public class V2026081503__RealtimeDiagnosisRuntime extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_realtime_diagnosis_policy (
              id BIGINT NOT NULL,
              device_id BIGINT NOT NULL,
              point_id BIGINT NOT NULL,
              model_type VARCHAR(16) NOT NULL,
              model_version VARCHAR(64) NULL,
              window_samples INT NOT NULL DEFAULT 5120,
              stride_samples INT NOT NULL DEFAULT 5120,
              min_interval_seconds INT NOT NULL DEFAULT 30,
              alarm_cooldown_seconds INT NOT NULL DEFAULT 300,
              enabled TINYINT(1) NOT NULL DEFAULT 0,
              remark VARCHAR(500) NULL,
              create_by VARCHAR(64) NULL,
              create_time DATETIME NULL,
              update_time DATETIME NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_rt_policy_point_model(point_id, model_type),
              KEY idx_rt_policy_device_enabled(device_id, enabled)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
            """);
        addColumn(connection, "sensor_inference_task", "source_type",
            "VARCHAR(16) NOT NULL DEFAULT 'MANUAL'");
        addColumn(connection, "sensor_inference_task", "window_id", "VARCHAR(128) NULL");
        addColumn(connection, "sensor_inference_task", "deadline_at", "DATETIME NULL");
        addColumn(connection, "sensor_inference_task", "queued_at", "DATETIME NULL");
        addColumn(connection, "sensor_inference_task", "attempt_count", "INT NOT NULL DEFAULT 0");
        addIndex(connection, "sensor_inference_task", "idx_task_source_status_time",
            "ALTER TABLE sensor_inference_task ADD INDEX idx_task_source_status_time(source_type,status,create_time)");
        addIndex(connection, "sensor_inference_task", "uk_task_window_id",
            "ALTER TABLE sensor_inference_task ADD UNIQUE KEY uk_task_window_id(window_id)");

        addColumn(connection, "enhanced_inference_record", "source_type",
            "VARCHAR(16) NOT NULL DEFAULT 'MANUAL'");
        addColumn(connection, "enhanced_inference_record", "window_id", "VARCHAR(128) NULL");
        addIndex(connection, "enhanced_inference_record", "idx_record_source_time",
            "ALTER TABLE enhanced_inference_record ADD INDEX idx_record_source_time(source_type,create_time)");
    }

    private void addColumn(Connection connection, String table, String column, String definition) throws Exception
    {
        if (!columnExists(connection, table, column))
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void addIndex(Connection connection, String table, String index, String ddl) throws Exception
    {
        if (!indexExists(connection, table, index)) execute(connection, ddl);
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception
    {
        try (ResultSet rows = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column))
        {
            return rows.next();
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws Exception
    {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false))
        {
            while (rows.next()) if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
            return false;
        }
    }

    private void execute(Connection connection, String sql) throws Exception
    {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
