package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Completes inference-record references that were present in the application
 * model and clean-install DDL but missing from the legacy-database upgrade path.
 */
public class V2026072302__InferenceTaskAndChannelReferences extends BaseJavaMigration
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

        addColumn(connection, "enhanced_inference_record", "task_id", "BIGINT NULL");
        addColumn(connection, "enhanced_inference_record", "channel_id", "INT NULL");

        if (columnExists(connection, "enhanced_inference_record", "channel_no"))
        {
            execute(connection, """
                UPDATE enhanced_inference_record
                   SET channel_id = channel_no
                 WHERE channel_id IS NULL
                   AND channel_no IS NOT NULL
                """);
        }

        addIndex(connection, "enhanced_inference_record", "idx_task_id",
            "ALTER TABLE enhanced_inference_record ADD INDEX idx_task_id (task_id)");
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
