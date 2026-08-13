package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** MySQL audit/synchronization state required by diagnosis_result in IoTDB. */
public class V2026072101__DiagnosisIotdbSync extends BaseJavaMigration
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
        if (!tableExists(connection, "enhanced_inference_record"))
        {
            throw new FlywayException("enhanced_inference_record is missing; install the diagnosis baseline first");
        }
        addColumn(connection, "enhanced_inference_record", "batch_id", "BIGINT NULL");
        addColumn(connection, "enhanced_inference_record", "frequency_json", "LONGTEXT NULL");
        addColumn(connection, "enhanced_inference_record", "result_json", "LONGTEXT NULL");
        addColumn(connection, "enhanced_inference_record", "iotdb_sync_status",
            "VARCHAR(16) NOT NULL DEFAULT 'PENDING'");
        addColumn(connection, "enhanced_inference_record", "iotdb_sync_attempts", "INT NOT NULL DEFAULT 0");
        addColumn(connection, "enhanced_inference_record", "iotdb_sync_time", "DATETIME NULL");
        addColumn(connection, "enhanced_inference_record", "iotdb_sync_error", "VARCHAR(1000) NULL");
        addColumn(connection, "enhanced_inference_record", "iotdb_result_ref", "VARCHAR(500) NULL");
        addColumn(connection, "enhanced_inference_record", "linkage_status",
            "VARCHAR(16) NOT NULL DEFAULT 'COMPLETED'");
        addColumn(connection, "enhanced_inference_record", "linkage_time", "DATETIME NULL");
        addColumn(connection, "enhanced_inference_record", "linkage_error", "VARCHAR(1000) NULL");
        addIndex(connection, "enhanced_inference_record", "idx_iotdb_sync_status",
            "ALTER TABLE enhanced_inference_record ADD INDEX idx_iotdb_sync_status (iotdb_sync_status,id)");
        addIndex(connection, "enhanced_inference_record", "idx_batch_id",
            "ALTER TABLE enhanced_inference_record ADD INDEX idx_batch_id (batch_id)");
        execute(connection, """
            CREATE TABLE IF NOT EXISTS sensor_diagnosis_iotdb_backfill_job (
              id BIGINT NOT NULL, status VARCHAR(16) NOT NULL, from_record_id BIGINT NULL,
              to_record_id BIGINT NULL, failed_only TINYINT(1) NOT NULL DEFAULT 0,
              batch_size INT NOT NULL DEFAULT 200, max_rows_per_second INT NOT NULL DEFAULT 100,
              cursor_record_id BIGINT NULL, processed_count INT NOT NULL DEFAULT 0,
              success_count INT NOT NULL DEFAULT 0, failed_count INT NOT NULL DEFAULT 0,
              last_error VARCHAR(1000) NULL, created_by VARCHAR(64) NOT NULL,
              create_time DATETIME NOT NULL, start_time DATETIME NULL, finish_time DATETIME NULL,
              update_time DATETIME NOT NULL, PRIMARY KEY(id),
              KEY idx_diagnosis_backfill_status(status,create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }

    private void addColumn(Connection connection, String table, String column, String definition) throws Exception
    {
        if (!columnExists(connection, table, column))
        {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void addIndex(Connection connection, String table, String index, String ddl) throws Exception
    {
        if (!indexExists(connection, table, index))
        {
            execute(connection, ddl);
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception
    {
        try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
            new String[] {"TABLE"}))
        {
            return rows.next();
        }
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
            while (rows.next())
            {
                if (index.equalsIgnoreCase(rows.getString("INDEX_NAME")))
                {
                    return true;
                }
            }
            return false;
        }
    }

    private void execute(Connection connection, String sql) throws Exception
    {
        try (Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }
}
