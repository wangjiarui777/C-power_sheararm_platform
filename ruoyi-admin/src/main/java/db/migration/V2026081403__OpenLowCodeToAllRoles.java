package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Grant the low-code workbench and runtime operations to every active role. */
public class V2026081403__OpenLowCodeToAllRoles extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        String menuSql = "SELECT menu_id FROM sys_menu WHERE "
                + "(path='lowcode' AND menu_type='C') "
                + "OR perms LIKE 'tool:lowcode:%' "
                + "OR perms LIKE 'lowcode:runtime:%'";
        try (PreparedStatement menus = connection.prepareStatement(menuSql);
                ResultSet menuRows = menus.executeQuery();
                PreparedStatement roles = connection.prepareStatement(
                        "SELECT role_id FROM sys_role WHERE status='0' AND del_flag='0'");
                ResultSet roleRows = roles.executeQuery();
                PreparedStatement grant = connection.prepareStatement(
                        "INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (?, ?)");)
        {
            java.util.List<Long> menuIds = new java.util.ArrayList<>();
            while (menuRows.next()) menuIds.add(menuRows.getLong(1));
            while (roleRows.next())
            {
                long roleId = roleRows.getLong(1);
                for (Long menuId : menuIds)
                {
                    grant.setLong(1, roleId);
                    grant.setLong(2, menuId);
                    grant.addBatch();
                }
            }
            grant.executeBatch();
        }
    }
}
