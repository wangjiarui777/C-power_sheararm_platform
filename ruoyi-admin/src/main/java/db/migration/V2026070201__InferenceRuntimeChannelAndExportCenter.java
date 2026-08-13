package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026070201__InferenceRuntimeChannelAndExportCenter extends BaseJavaMigration
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
        addColumn(connection, "enhanced_inference_record", "channel_no",
            "ALTER TABLE enhanced_inference_record ADD COLUMN channel_no INT NULL AFTER fault_part_code");
        execute(connection, """
            UPDATE enhanced_inference_record r
            JOIN phm_acquisition_channel c ON r.acquisition_channel_id = c.id
            SET r.channel_no = c.channel_no
            WHERE r.channel_no IS NULL
            """);
        addIndex(connection, "enhanced_inference_record", "idx_inference_record_channel_time",
            "ALTER TABLE enhanced_inference_record ADD INDEX idx_inference_record_channel_time (device_code, channel_no, sample_time)");
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
