package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026062303__ModelShadowRun extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        addColumn(connection, "sensor_model_release", "shadow_start_time",
            "ALTER TABLE sensor_model_release ADD COLUMN shadow_start_time DATETIME NULL AFTER shadow_days");
        addColumn(connection, "sensor_model_release", "shadow_end_time",
            "ALTER TABLE sensor_model_release ADD COLUMN shadow_end_time DATETIME NULL AFTER shadow_start_time");
        addColumn(connection, "sensor_model_release", "shadow_result_status",
            "ALTER TABLE sensor_model_release ADD COLUMN shadow_result_status VARCHAR(16) NULL AFTER shadow_end_time");
    }

    private void addColumn(Connection connection, String table, String column, String ddl) throws Exception
    {
        try (ResultSet rows = connection.getMetaData()
            .getColumns(connection.getCatalog(), null, table, column))
        {
            if (!rows.next())
            {
                try (Statement statement = connection.createStatement())
                {
                    statement.execute(ddl);
                }
            }
        }
    }
}
