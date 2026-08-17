package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Removes the retired System Tools navigation without deleting low-code code. */
public class V2026081702__RemoveSystemToolsMenu extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        if (!tableExists(connection, "sys_menu")) return;

        List<Long> menuIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT menu_id FROM sys_menu
            WHERE parent_id=0 AND (path='tool' OR menu_name='系统工具')
            ORDER BY menu_id
            """))
        {
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next()) menuIds.add(rows.getLong(1));
            }
        }

        for (Long rootId : menuIds)
        {
            collectDescendants(connection, rootId, menuIds);
        }

        if (menuIds.isEmpty()) return;

        String placeholders = String.join(",", java.util.Collections.nCopies(menuIds.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM sys_role_menu WHERE menu_id IN (" + placeholders + ")"))
        {
            bindIds(statement, menuIds);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM sys_menu WHERE menu_id IN (" + placeholders + ")"))
        {
            bindIds(statement, menuIds);
            statement.executeUpdate();
        }
    }

    private void collectDescendants(Connection connection, long parentId, List<Long> menuIds)
        throws Exception
    {
        List<Long> children = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=?"))
        {
            statement.setLong(1, parentId);
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next()) children.add(rows.getLong(1));
            }
        }
        for (Long childId : children)
        {
            if (!menuIds.contains(childId))
            {
                menuIds.add(childId);
                collectDescendants(connection, childId, menuIds);
            }
        }
    }

    private void bindIds(PreparedStatement statement, List<Long> ids) throws Exception
    {
        for (int i = 0; i < ids.size(); i++) statement.setLong(i + 1, ids.get(i));
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?"))
        {
            statement.setString(1, tableName);
            try (ResultSet rows = statement.executeQuery())
            {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }
}
