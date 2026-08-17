package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Removes the retired project-level permission settings from built-in templates. */
public class V2026081703__RemoveLowCodeProjectPermissions extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        try (Statement statement = context.getConnection().createStatement())
        {
            statement.execute("""
                UPDATE lc_template
                SET metadata_json = JSON_REMOVE(metadata_json, '$.permissions')
                WHERE JSON_VALID(metadata_json)
                  AND JSON_CONTAINS_PATH(metadata_json, 'one', '$.permissions')
                """);
        }
    }
}
