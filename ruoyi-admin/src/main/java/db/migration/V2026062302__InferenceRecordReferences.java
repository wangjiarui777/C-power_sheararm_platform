package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026062302__InferenceRecordReferences extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        addColumn(connection, "enhanced_inference_record", "timeseries_ref",
            "ALTER TABLE enhanced_inference_record ADD COLUMN timeseries_ref VARCHAR(512) NULL AFTER spectrum_json");
        addColumn(connection, "enhanced_inference_record", "model_release_id",
            "ALTER TABLE enhanced_inference_record ADD COLUMN model_release_id BIGINT NULL AFTER timeseries_ref");
        addIndex(connection, "enhanced_inference_record", "idx_inference_model_release",
            "ALTER TABLE enhanced_inference_record ADD INDEX idx_inference_model_release (model_release_id)");
    }

    private void addColumn(Connection connection, String table, String column, String ddl) throws Exception
    {
        if (!exists(connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)))
        {
            execute(connection, ddl);
        }
    }

    private void addIndex(Connection connection, String table, String index, String ddl) throws Exception
    {
        if (!exists(connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false),
            "INDEX_NAME", index))
        {
            execute(connection, ddl);
        }
    }

    private boolean exists(ResultSet rows) throws Exception
    {
        try (rows)
        {
            return rows.next();
        }
    }

    private boolean exists(ResultSet rows, String field, String expected) throws Exception
    {
        try (rows)
        {
            while (rows.next())
            {
                if (expected.equalsIgnoreCase(rows.getString(field)))
                {
                    return true;
                }
            }
            return false;
        }
    }

    private void execute(Connection connection, String ddl) throws Exception
    {
        try (Statement statement = connection.createStatement())
        {
            statement.execute(ddl);
        }
    }
}
