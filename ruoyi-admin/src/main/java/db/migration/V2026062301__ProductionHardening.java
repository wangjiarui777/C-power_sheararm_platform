package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026062301__ProductionHardening extends BaseJavaMigration
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
        if (!tableExists(connection, "sys_user"))
        {
            throw new FlywayException("RuoYi baseline is missing. Install the approved V1 baseline before production migrations.");
        }
        createTables(connection);
        addColumn(connection, "phm_device", "dept_id",
            "ALTER TABLE phm_device ADD COLUMN dept_id BIGINT NULL COMMENT 'Owning RuoYi department' AFTER id");
        addColumn(connection, "phm_attachment", "object_name",
            "ALTER TABLE phm_attachment ADD COLUMN object_name VARCHAR(128) NULL AFTER file_url");
        addColumn(connection, "phm_attachment", "storage_path",
            "ALTER TABLE phm_attachment ADD COLUMN storage_path VARCHAR(1000) NULL AFTER object_name");
        addColumn(connection, "phm_attachment", "mime_type",
            "ALTER TABLE phm_attachment ADD COLUMN mime_type VARCHAR(128) NULL AFTER file_ext");
        addColumn(connection, "phm_attachment", "file_size",
            "ALTER TABLE phm_attachment ADD COLUMN file_size BIGINT NULL AFTER mime_type");
        addColumn(connection, "phm_attachment", "sha256",
            "ALTER TABLE phm_attachment ADD COLUMN sha256 CHAR(64) NULL AFTER file_size");
        addColumn(connection, "phm_attachment", "scan_status",
            "ALTER TABLE phm_attachment ADD COLUMN scan_status VARCHAR(16) NULL AFTER sha256");
        addColumn(connection, "phm_attachment", "purpose",
            "ALTER TABLE phm_attachment ADD COLUMN purpose VARCHAR(32) NULL AFTER scan_status");
        addColumn(connection, "device_vibration_data", "event_id",
            "ALTER TABLE device_vibration_data ADD COLUMN event_id VARCHAR(64) NULL AFTER data_id");
        addColumn(connection, "device_temperature_data", "event_id",
            "ALTER TABLE device_temperature_data ADD COLUMN event_id VARCHAR(64) NULL AFTER data_id");
        addIndex(connection, "phm_device", "idx_phm_device_dept",
            "ALTER TABLE phm_device ADD INDEX idx_phm_device_dept (dept_id)");
        addIndex(connection, "device_vibration_data", "uk_vibration_event_id",
            "ALTER TABLE device_vibration_data ADD UNIQUE INDEX uk_vibration_event_id (event_id)");
        addIndex(connection, "device_temperature_data", "uk_temperature_event_id",
            "ALTER TABLE device_temperature_data ADD UNIQUE INDEX uk_temperature_event_id (event_id)");
    }

    private void createTables(Connection connection) throws SQLException
    {
        execute(connection, """
            CREATE TABLE IF NOT EXISTS sensor_inference_task (
              id BIGINT NOT NULL, request_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) NULL,
              device_code VARCHAR(64) NOT NULL, point_id BIGINT NULL, channel_id INT NULL,
              model_type VARCHAR(32) NOT NULL, requested_model_version VARCHAR(128) NULL,
              input_type VARCHAR(32) NOT NULL, input_ref VARCHAR(512) NOT NULL, input_sha256 CHAR(64) NULL,
              status VARCHAR(16) NOT NULL, error_code VARCHAR(64) NULL, error_message VARCHAR(1000) NULL,
              input_json LONGTEXT NULL, result_json LONGTEXT NULL, created_by VARCHAR(64) NOT NULL,
              create_time DATETIME NOT NULL, start_time DATETIME NULL, finish_time DATETIME NULL,
              update_time DATETIME NOT NULL, PRIMARY KEY(id),
              UNIQUE KEY uk_inference_task_request(request_id),
              UNIQUE KEY uk_inference_task_idempotency(idempotency_key),
              KEY idx_inference_task_device_time(device_code,create_time),
              KEY idx_inference_task_status_time(status,create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        execute(connection, """
            CREATE TABLE IF NOT EXISTS sensor_model_release (
              id BIGINT NOT NULL, model_name VARCHAR(128) NOT NULL, model_type VARCHAR(32) NOT NULL,
              semantic_version VARCHAR(64) NOT NULL, file_sha256 CHAR(64) NOT NULL,
              training_data_version VARCHAR(128) NOT NULL, validation_data_version VARCHAR(128) NOT NULL,
              threshold_version VARCHAR(128) NOT NULL, precision_score DECIMAL(8,6) NULL,
              recall_score DECIMAL(8,6) NULL, severe_recall_score DECIMAL(8,6) NULL,
              false_positive_per_device_day DECIMAL(10,4) NULL, confidence_threshold DECIMAL(8,4) NULL,
              consecutive_hits INT NOT NULL DEFAULT 1, shadow_days INT NOT NULL DEFAULT 0,
              cooldown_minutes INT NOT NULL DEFAULT 60, status VARCHAR(16) NOT NULL,
              artifact_uri VARCHAR(512) NULL, created_by VARCHAR(64) NOT NULL, activated_by VARCHAR(64) NULL,
              create_time DATETIME NOT NULL, activate_time DATETIME NULL, update_time DATETIME NOT NULL,
              remark VARCHAR(1000) NULL, PRIMARY KEY(id),
              UNIQUE KEY uk_model_release_version(model_type,semantic_version),
              UNIQUE KEY uk_model_release_sha(file_sha256), KEY idx_model_release_status(model_type,status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        execute(connection, """
            CREATE TABLE IF NOT EXISTS sensor_collector_credential (
              id BIGINT NOT NULL, collector_id VARCHAR(64) NOT NULL, collector_name VARCHAR(128) NULL,
              encrypted_secret VARCHAR(512) NOT NULL, secret_hash CHAR(64) NOT NULL,
              allowed_devices TEXT NOT NULL, enabled TINYINT(1) NOT NULL DEFAULT 1,
              expire_time DATETIME NULL, last_online_time DATETIME NULL, last_ip VARCHAR(64) NULL,
              created_by VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL, update_time DATETIME NOT NULL,
              remark VARCHAR(500) NULL, PRIMARY KEY(id), UNIQUE KEY uk_collector_id(collector_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }

    private void addColumn(Connection connection, String table, String column, String ddl) throws SQLException
    {
        if (!columnExists(connection, table, column))
        {
            execute(connection, ddl);
        }
    }

    private void addIndex(Connection connection, String table, String index, String ddl) throws SQLException
    {
        if (!indexExists(connection, table, index))
        {
            execute(connection, ddl);
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[] {"TABLE"}))
        {
            return result.next();
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column))
        {
            return result.next();
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false))
        {
            while (result.next())
            {
                if (index.equalsIgnoreCase(result.getString("INDEX_NAME")))
                {
                    return true;
                }
            }
            return false;
        }
    }

    private void execute(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }
}
