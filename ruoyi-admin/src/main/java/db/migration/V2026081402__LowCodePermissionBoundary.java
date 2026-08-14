package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Align low-code menu grants with the controller permission contract. */
public class V2026081402__LowCodePermissionBoundary extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        try (Statement statement = context.getConnection().createStatement())
        {
            // The previous seed copied every low-code function to every role that
            // had the System Tools menu. Keep the design entry point, but require
            // explicit grants for privileged operations after the upgrade.
            statement.execute("""
                DELETE rm FROM sys_role_menu rm
                JOIN sys_menu m ON m.menu_id = rm.menu_id
                JOIN sys_role r ON r.role_id = rm.role_id
                WHERE m.menu_type='F'
                  AND m.perms IN ('tool:lowcode:validate','tool:lowcode:publish',
                    'tool:lowcode:rollback','tool:lowcode:connector',
                    'lowcode:runtime:query','lowcode:runtime:add',
                    'lowcode:runtime:edit','lowcode:runtime:remove','lowcode:runtime:action')
                  AND COALESCE(r.role_key, '') <> 'admin'
                """);
        }
    }
}
