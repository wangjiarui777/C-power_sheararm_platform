package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keeps visible sidebar entries on distinct, semantically useful icons. */
public class V2026081401__NormalizeMenuIcons extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        updateRootIcon(connection, "analysis-toolkit", "skill");
        updateRootIcon(connection, "phm", "component");
        updateChildIcon(connection, "monitoring-center", "vibration", "row");
        updateChildIcon(connection, "monitoring-center", "oil", "rate");
        updateChildIcon(connection, "analysis-toolkit", "bearing-diagnosis", "bug");
        updateChildIcon(connection, "phm", "cluster", "nested");
        updateChildIcon(connection, "phm", "config", "color");
        updateChildIcon(connection, "tool", "lowcode", "example");
    }

    private void updateRootIcon(Connection connection, String path, String icon) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE sys_menu SET icon=? WHERE parent_id=0 AND path=?"))
        {
            statement.setString(1, icon);
            statement.setString(2, path);
            statement.executeUpdate();
        }
    }

    private void updateChildIcon(Connection connection, String parentPath, String path, String icon)
        throws Exception
    {
        String sql = "UPDATE sys_menu SET icon=? WHERE parent_id=("
            + "SELECT menu_id FROM (SELECT menu_id FROM sys_menu "
            + "WHERE parent_id=0 AND path=? LIMIT 1) AS parent_menu) AND path=?";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, icon);
            statement.setString(2, parentPath);
            statement.setString(3, path);
            statement.executeUpdate();
        }
    }
}
