package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026062504__IndustrialChannelDiagnosisBinding extends BaseJavaMigration
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
        createTables(connection);
        addColumn(connection, "sensor_inference_task", "acquisition_channel_id",
            "ALTER TABLE sensor_inference_task ADD COLUMN acquisition_channel_id BIGINT NULL");
        addColumn(connection, "sensor_inference_task", "collector_id",
            "ALTER TABLE sensor_inference_task ADD COLUMN collector_id VARCHAR(64) NULL");
        addColumn(connection, "enhanced_inference_record", "point_id",
            "ALTER TABLE enhanced_inference_record ADD COLUMN point_id BIGINT NULL");
        addColumn(connection, "enhanced_inference_record", "acquisition_channel_id",
            "ALTER TABLE enhanced_inference_record ADD COLUMN acquisition_channel_id BIGINT NULL");
        addColumn(connection, "enhanced_inference_record", "collector_id",
            "ALTER TABLE enhanced_inference_record ADD COLUMN collector_id VARCHAR(64) NULL");
        addColumn(connection, "enhanced_inference_record", "input_ref",
            "ALTER TABLE enhanced_inference_record ADD COLUMN input_ref VARCHAR(512) NULL");
        addColumn(connection, "enhanced_inference_record", "model_version",
            "ALTER TABLE enhanced_inference_record ADD COLUMN model_version VARCHAR(128) NULL");
        addIndex(connection, "phm_acquisition_channel", "idx_phm_channel_device_point",
            "ALTER TABLE phm_acquisition_channel ADD INDEX idx_phm_channel_device_point (device_id, point_id)");
        addIndex(connection, "phm_diagnosis_binding", "idx_phm_binding_device_point",
            "ALTER TABLE phm_diagnosis_binding ADD INDEX idx_phm_binding_device_point (device_id, point_id)");
        addIndex(connection, "enhanced_inference_record", "idx_inference_record_point_time",
            "ALTER TABLE enhanced_inference_record ADD INDEX idx_inference_record_point_time (point_id, sample_time)");
        backfill(connection);
    }

    private void createTables(Connection connection) throws SQLException
    {
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_acquisition_channel (
              id BIGINT NOT NULL,
              collector_id VARCHAR(64) NOT NULL,
              module_no INT NOT NULL DEFAULT 1,
              channel_no INT NOT NULL,
              device_id BIGINT NOT NULL,
              device_code VARCHAR(64) NOT NULL,
              point_id BIGINT NULL,
              point_code VARCHAR(64) NULL,
              signal_type VARCHAR(32) NULL,
              sample_rate DECIMAL(12,2) NULL,
              unit VARCHAR(32) NULL,
              scale_factor DECIMAL(18,8) NOT NULL DEFAULT 1,
              offset_value DECIMAL(18,8) NOT NULL DEFAULT 0,
              quality_policy_seconds INT NOT NULL DEFAULT 300,
              enabled TINYINT(1) NOT NULL DEFAULT 1,
              create_time DATETIME NOT NULL,
              update_time DATETIME NOT NULL,
              remark VARCHAR(500) NULL,
              PRIMARY KEY(id),
              UNIQUE KEY uk_phm_channel_collector_module_no (collector_id, module_no, channel_no),
              KEY idx_phm_channel_device_code (device_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_diagnosis_binding (
              id BIGINT NOT NULL,
              device_id BIGINT NOT NULL,
              device_code VARCHAR(64) NOT NULL,
              point_id BIGINT NOT NULL,
              channel_id BIGINT NOT NULL,
              model_type VARCHAR(32) NOT NULL,
              model_version VARCHAR(128) NULL,
              input_mode VARCHAR(32) NOT NULL DEFAULT 'ATTACHMENT',
              window_size INT NOT NULL DEFAULT 2048,
              stride INT NULL,
              trigger_policy VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
              min_confidence DECIMAL(8,4) NULL,
              enabled TINYINT(1) NOT NULL DEFAULT 1,
              create_time DATETIME NOT NULL,
              update_time DATETIME NOT NULL,
              remark VARCHAR(500) NULL,
              PRIMARY KEY(id),
              UNIQUE KEY uk_phm_binding_point_model_input (point_id, model_type, input_mode),
              KEY idx_phm_binding_channel (channel_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }

    private void backfill(Connection connection) throws SQLException
    {
        if (!tableExists(connection, "phm_measure_point") || !tableExists(connection, "phm_device"))
        {
            return;
        }
        execute(connection, """
            INSERT IGNORE INTO phm_acquisition_channel
              (id, collector_id, module_no, channel_no, device_id, device_code, point_id, point_code,
               signal_type, sample_rate, unit, scale_factor, offset_value, quality_policy_seconds,
               enabled, create_time, update_time, remark)
            SELECT p.id, 'default-collector', COALESCE(p.device_id, 1), COALESCE(p.channel_id, 1),
                   p.device_id, p.device_code, p.id, p.point_code, p.signal_type, NULL,
                   COALESCE(p.unit, CASE WHEN p.signal_type = 'temperature' THEN '℃' ELSE 'mm/s' END),
                   1, 0,
                   CASE WHEN p.quality_policy REGEXP '^[0-9]+$' THEN CAST(p.quality_policy AS UNSIGNED) ELSE 300 END,
                   COALESCE(p.enabled, 1), NOW(), NOW(), 'Migrated from phm_measure_point.channel_id'
            FROM phm_measure_point p
            WHERE p.channel_id IS NOT NULL
              AND p.device_id IS NOT NULL
            """);
        execute(connection, """
            INSERT IGNORE INTO phm_diagnosis_binding
              (id, device_id, device_code, point_id, channel_id, model_type, model_version, input_mode,
               window_size, stride, trigger_policy, min_confidence, enabled, create_time, update_time, remark)
            SELECT p.id, p.device_id, p.device_code, p.id, c.id,
                   CASE
                     WHEN LOWER(COALESCE(d.device_type, '')) LIKE '%bearing%' OR COALESCE(d.device_type, '') LIKE '%轴承%' THEN 'bearing'
                     WHEN LOWER(COALESCE(d.device_type, '')) LIKE '%gear%' OR COALESCE(d.device_type, '') LIKE '%齿轮%' THEN 'gear'
                     ELSE 'gear'
                   END,
                   NULL, 'ATTACHMENT', 2048, NULL, 'MANUAL', NULL, 1, NOW(), NOW(),
                   'Migrated default diagnosis binding'
            FROM phm_measure_point p
            JOIN phm_acquisition_channel c ON c.point_id = p.id
            LEFT JOIN phm_device d ON d.id = p.device_id
            WHERE COALESCE(p.signal_type, 'vibration') = 'vibration'
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

    private boolean columnExists(Connection connection, String table, String column) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column))
        {
            return result.next();
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[] {"TABLE"}))
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
