package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Runtime ledgers for the industrial low-code pipeline and unified file intake. */
public class V2026081501__IndustrialPipelineRuntime extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement())
        {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS sensor_ingest_file (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  source_type VARCHAR(24) NOT NULL,
                  source_ref VARCHAR(255) NULL,
                  attachment_id BIGINT NULL,
                  device_id BIGINT NULL,
                  device_code VARCHAR(64) NULL,
                  point_id BIGINT NULL,
                  point_code VARCHAR(64) NULL,
                  channel_id INT NULL,
                  file_name VARCHAR(255) NOT NULL,
                  file_ext VARCHAR(16) NULL,
                  file_size BIGINT NULL,
                  sha256 CHAR(64) NULL,
                  status VARCHAR(24) NOT NULL DEFAULT 'RECEIVING',
                  error_code VARCHAR(64) NULL,
                  error_message VARCHAR(1000) NULL,
                  retry_count INT NOT NULL DEFAULT 0,
                  received_time DATETIME NOT NULL,
                  validated_time DATETIME NULL,
                  create_by VARCHAR(64) NULL,
                  create_time DATETIME NOT NULL,
                  update_time DATETIME NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_sensor_ingest_sha_point (sha256, point_id),
                  KEY idx_sensor_ingest_status_time (status, received_time),
                  KEY idx_sensor_ingest_device_point (device_code, point_code, received_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一振动文件接收账本'
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_pipeline_activation (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  app_code VARCHAR(64) NOT NULL,
                  version_id BIGINT NOT NULL,
                  checksum CHAR(64) NOT NULL,
                  cron_expression VARCHAR(128) NOT NULL,
                  time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Hong_Kong',
                  enabled TINYINT(1) NOT NULL DEFAULT 0,
                  status VARCHAR(24) NOT NULL DEFAULT 'REQUIRES_TEST',
                  activated_by VARCHAR(64) NULL,
                  activated_user_id BIGINT NULL,
                  activated_dept_id BIGINT NULL,
                  last_run_time DATETIME NULL,
                  last_run_status VARCHAR(24) NULL,
                  create_time DATETIME NOT NULL,
                  update_time DATETIME NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_lc_pipeline_activation_app (app_code),
                  KEY idx_lc_pipeline_activation_enabled (enabled, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码诊断管道启停账本'
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_pipeline_run (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  app_code VARCHAR(64) NOT NULL,
                  version_id BIGINT NOT NULL,
                  checksum CHAR(64) NOT NULL,
                  trigger_type VARCHAR(16) NOT NULL,
                  binding_count INT NOT NULL DEFAULT 0,
                  success_count INT NOT NULL DEFAULT 0,
                  skipped_count INT NOT NULL DEFAULT 0,
                  failed_count INT NOT NULL DEFAULT 0,
                  status VARCHAR(24) NOT NULL,
                  detail_json LONGTEXT NULL,
                  idempotency_key VARCHAR(128) NULL,
                  started_at DATETIME NOT NULL,
                  finished_at DATETIME NULL,
                  create_by VARCHAR(64) NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_lc_pipeline_run_idempotency (app_code, idempotency_key),
                  KEY idx_lc_pipeline_run_app_time (app_code, started_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码诊断管道运行记录'
                """);
        }
        insertPipelineTemplate(connection);
    }

    private void insertPipelineTemplate(Connection connection) throws Exception
    {
        String metadata = """
            {"schemaVersion":2,"appType":"SENSOR_DIAGNOSIS_PIPELINE","preset":"sensor-diagnosis-pipeline","pipeline":{"bindings":[],"iotdb":{"database":"monitoring","table":"vibration_frame","tagMapping":{"deviceCode":"device_code","pointCode":"point_code"},"fieldMapping":{"waveform":"waveform","sampleRate":"sample_rate","sampleCount":"sample_count","quality":"quality","sequence":"sequence","receiveTime":"receive_time"},"maxFrameAgeSeconds":300,"acceptedQuality":["GOOD"]},"trigger":{"manual":true,"schedule":{"cron":"0 0/15 * * * ?","timeZone":"Asia/Hong_Kong"}}},"permissions":{"dataScope":"NONE"}}
            """.trim();
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT IGNORE INTO lc_template(template_code,template_name,metadata_json,builtin,create_time)
            VALUES(?,?,?,1,NOW())
            """))
        {
            statement.setString(1, "sensor-diagnosis-pipeline");
            statement.setString(2, "工业诊断管道");
            statement.setString(3, metadata);
            statement.executeUpdate();
        }
    }
}
