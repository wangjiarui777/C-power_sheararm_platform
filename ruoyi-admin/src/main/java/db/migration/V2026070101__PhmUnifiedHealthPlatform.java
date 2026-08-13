package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026070101__PhmUnifiedHealthPlatform extends BaseJavaMigration
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
        addColumn(connection, "phm_device", "project_id", "ALTER TABLE phm_device ADD COLUMN project_id BIGINT NULL AFTER id");
        addColumn(connection, "phm_device", "line_code", "ALTER TABLE phm_device ADD COLUMN line_code VARCHAR(64) NULL AFTER dept_id");
        addColumn(connection, "phm_device", "model_id", "ALTER TABLE phm_device ADD COLUMN model_id BIGINT NULL AFTER device_type");
        addColumn(connection, "phm_measure_point", "part_id", "ALTER TABLE phm_measure_point ADD COLUMN part_id BIGINT NULL AFTER device_code");
        addColumn(connection, "phm_measure_point", "part_code", "ALTER TABLE phm_measure_point ADD COLUMN part_code VARCHAR(64) NULL AFTER part_id");
        addColumn(connection, "phm_acquisition_channel", "sensor_model", "ALTER TABLE phm_acquisition_channel ADD COLUMN sensor_model VARCHAR(128) NULL AFTER quality_policy_seconds");
        addColumn(connection, "phm_acquisition_channel", "mount_type", "ALTER TABLE phm_acquisition_channel ADD COLUMN mount_type VARCHAR(32) NULL AFTER sensor_model");
        addColumn(connection, "phm_acquisition_channel", "mount_position", "ALTER TABLE phm_acquisition_channel ADD COLUMN mount_position VARCHAR(128) NULL AFTER mount_type");
        addColumn(connection, "phm_acquisition_channel", "sensitivity", "ALTER TABLE phm_acquisition_channel ADD COLUMN sensitivity DECIMAL(12,4) NULL AFTER mount_position");
        addColumn(connection, "phm_acquisition_channel", "range_value", "ALTER TABLE phm_acquisition_channel ADD COLUMN range_value DECIMAL(12,4) NULL AFTER sensitivity");
        addColumn(connection, "phm_acquisition_channel", "calibration_date", "ALTER TABLE phm_acquisition_channel ADD COLUMN calibration_date DATE NULL AFTER range_value");
        addColumn(connection, "phm_alarm_event", "project_id", "ALTER TABLE phm_alarm_event ADD COLUMN project_id BIGINT NULL AFTER alarm_no");
        addColumn(connection, "phm_alarm_event", "part_id", "ALTER TABLE phm_alarm_event ADD COLUMN part_id BIGINT NULL AFTER point_name");
        addColumn(connection, "phm_alarm_event", "part_code", "ALTER TABLE phm_alarm_event ADD COLUMN part_code VARCHAR(64) NULL AFTER part_id");
        addColumn(connection, "enhanced_inference_record", "project_id", "ALTER TABLE enhanced_inference_record ADD COLUMN project_id BIGINT NULL AFTER batch_id");
        addColumn(connection, "enhanced_inference_record", "part_id", "ALTER TABLE enhanced_inference_record ADD COLUMN part_id BIGINT NULL AFTER point_id");
        addColumn(connection, "enhanced_inference_record", "part_code", "ALTER TABLE enhanced_inference_record ADD COLUMN part_code VARCHAR(64) NULL AFTER part_id");
        addColumn(connection, "enhanced_inference_record", "fault_part_code", "ALTER TABLE enhanced_inference_record ADD COLUMN fault_part_code VARCHAR(64) NULL AFTER part_code");
        addColumn(connection, "enhanced_inference_record", "rpm", "ALTER TABLE enhanced_inference_record ADD COLUMN rpm DOUBLE NULL AFTER peak");
        addColumn(connection, "enhanced_inference_record", "power_spectrum_json", "ALTER TABLE enhanced_inference_record ADD COLUMN power_spectrum_json LONGTEXT NULL AFTER spectrum_json");
        addColumn(connection, "enhanced_inference_record", "envelope_spectrum_json", "ALTER TABLE enhanced_inference_record ADD COLUMN envelope_spectrum_json LONGTEXT NULL AFTER power_spectrum_json");
        addColumn(connection, "enhanced_inference_record", "order_spectrum_json", "ALTER TABLE enhanced_inference_record ADD COLUMN order_spectrum_json LONGTEXT NULL AFTER envelope_spectrum_json");
        addIndex(connection, "phm_device", "idx_phm_device_project", "ALTER TABLE phm_device ADD INDEX idx_phm_device_project (project_id)");
        addIndex(connection, "phm_measure_point", "idx_phm_point_part", "ALTER TABLE phm_measure_point ADD INDEX idx_phm_point_part (part_id, part_code)");
        addIndex(connection, "enhanced_inference_record", "idx_inference_record_multidim", "ALTER TABLE enhanced_inference_record ADD INDEX idx_inference_record_multidim (project_id, device_code, point_id, analysis_mode, risk_level, sample_time)");
        seedDefaults(connection);
    }

    private void createTables(Connection connection) throws SQLException
    {
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_project (
              id BIGINT NOT NULL,
              project_code VARCHAR(64) NOT NULL,
              project_name VARCHAR(128) NOT NULL,
              line_code VARCHAR(64) NULL,
              owner_org VARCHAR(128) NULL,
              status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
              description VARCHAR(1000) NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
              remark VARCHAR(500) NULL,
              PRIMARY KEY(id),
              UNIQUE KEY uk_phm_project_code (project_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM project and production line classification'
            """);
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_device_model (
              id BIGINT NOT NULL,
              model_code VARCHAR(64) NOT NULL,
              model_name VARCHAR(128) NOT NULL,
              equipment_type VARCHAR(64) NULL,
              manufacturer VARCHAR(128) NULL,
              glb_url VARCHAR(500) NULL,
              preview_url VARCHAR(500) NULL,
              part_map_json LONGTEXT NULL,
              enabled TINYINT(1) NOT NULL DEFAULT 1,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
              remark VARCHAR(500) NULL,
              PRIMARY KEY(id),
              UNIQUE KEY uk_phm_device_model_code (model_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM equipment model and 3D asset'
            """);
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_device_part (
              id BIGINT NOT NULL,
              model_id BIGINT NULL,
              device_id BIGINT NULL,
              part_code VARCHAR(64) NOT NULL,
              part_name VARCHAR(128) NOT NULL,
              parent_part_code VARCHAR(64) NULL,
              mesh_name VARCHAR(128) NULL,
              fault_codes VARCHAR(500) NULL,
              display_order INT NOT NULL DEFAULT 0,
              enabled TINYINT(1) NOT NULL DEFAULT 1,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
              remark VARCHAR(500) NULL,
              PRIMARY KEY(id),
              KEY idx_phm_part_model (model_id),
              KEY idx_phm_part_device (device_id),
              KEY idx_phm_part_code (part_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM equipment model/device part map'
            """);
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_simulation_layout (
              id BIGINT NOT NULL,
              model_id BIGINT NULL,
              part_id BIGINT NULL,
              analysis_type VARCHAR(64) NOT NULL DEFAULT 'HARMONIC_RESPONSE',
              point_code VARCHAR(64) NOT NULL,
              point_name VARCHAR(128) NULL,
              coverage_area VARCHAR(255) NULL,
              layout_reason VARCHAR(1000) NULL,
              sensor_axis VARCHAR(32) NULL,
              priority INT NOT NULL DEFAULT 5,
              file_url VARCHAR(500) NULL,
              enabled TINYINT(1) NOT NULL DEFAULT 1,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
              remark VARCHAR(500) NULL,
              PRIMARY KEY(id),
              KEY idx_phm_sim_model_part (model_id, part_id),
              KEY idx_phm_sim_type (analysis_type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM simulation analysis and measuring point layout'
            """);
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_data_retention_policy (
              id BIGINT NOT NULL,
              policy_name VARCHAR(128) NOT NULL,
              signal_type VARCHAR(32) NOT NULL DEFAULT 'vibration',
              stability_window_hours INT NOT NULL DEFAULT 72,
              max_variation_rate DECIMAL(12,6) NULL,
              raw_ttl_days INT NOT NULL DEFAULT 90,
              aggregate_ttl_days INT NOT NULL DEFAULT 1095,
              preserve_alarm_window_hours INT NOT NULL DEFAULT 24,
              enabled TINYINT(1) NOT NULL DEFAULT 1,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
              remark VARCHAR(500) NULL,
              PRIMARY KEY(id),
              KEY idx_phm_retention_signal (signal_type, enabled)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM stable raw data retention policy'
            """);
    }

    private void seedDefaults(Connection connection) throws SQLException
    {
        execute(connection, """
            INSERT IGNORE INTO phm_project
              (id, project_code, project_name, line_code, owner_org, status, description, remark)
            VALUES
              (200000000000000001, 'CL-ROCKER-BASE', '创力摇臂健康诊断示范项目', 'ROCKER-LINE-01', '创力平台', 'ACTIVE',
               '统一承载振动、油品、应力应变和仿真测点布局数据。', 'Seed project for unified PHM platform')
            """);
        execute(connection, """
            INSERT IGNORE INTO phm_device_model
              (id, model_code, model_name, equipment_type, manufacturer, glb_url, part_map_json, remark)
            VALUES
              (200000000000000101, 'ROCKER-ARM-GENERIC', '摇臂通用模型', '摇臂', '创力',
               '', '{"DRIVE_END":"drive_end","GEAR_MESH":"gear_mesh","SUPPORT":"support","CONNECTOR":"connector"}',
               'Default GLB mapping placeholder')
            """);
        execute(connection, """
            INSERT IGNORE INTO phm_data_retention_policy
              (id, policy_name, signal_type, stability_window_hours, max_variation_rate, raw_ttl_days,
               aggregate_ttl_days, preserve_alarm_window_hours, remark)
            VALUES
              (200000000000000201, '振动平稳原始帧清理策略', 'vibration', 72, 0.005000, 90, 1095, 24,
               'Only stable high-frequency raw frames are eligible; diagnosis records, alarms, attachments and aggregates are retained.')
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
