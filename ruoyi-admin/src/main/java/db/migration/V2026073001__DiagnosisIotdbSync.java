package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Creates the durable outbox used to synchronize diagnosis records to IoTDB. */
public class V2026073001__DiagnosisIotdbSync extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE IF NOT EXISTS diagnosis_iotdb_sync ("
                + "record_id BIGINT NOT NULL COMMENT 'enhanced_inference_record主键',"
                + "sync_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RETRY/SYNCED',"
                + "attempt_count INT NOT NULL DEFAULT 0,"
                + "next_retry_time DATETIME NULL,"
                + "lease_owner VARCHAR(64) NULL,"
                + "locked_until DATETIME NULL,"
                + "last_error VARCHAR(1000) NULL,"
                + "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "synced_time DATETIME NULL,"
                + "PRIMARY KEY (record_id),"
                + "KEY idx_diagnosis_sync_pending (sync_status, next_retry_time, locked_until),"
                + "KEY idx_diagnosis_sync_synced_time (synced_time)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci "
                + "COMMENT='诊断结果IoTDB可靠同步账本'");
        }
    }
}
