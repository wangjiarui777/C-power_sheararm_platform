package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Moves online users into System Management and removes the System Monitor navigation root. */
public class V2026081707__MergeOnlineUsersIntoSystemManagement extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        if (!tableExists(connection, "sys_menu")) return;

        Long systemRoot = findRoot(connection, "system", "系统管理");
        Long monitorRoot = findRoot(connection, "monitor", "系统监控");
        if (systemRoot == null)
        {
            if (monitorRoot != null) deleteMenuTree(connection, monitorRoot);
            return;
        }

        Long sourceOnline = monitorRoot == null ? null : findChild(connection, monitorRoot, "online", "在线用户");
        Long targetOnline = findChild(connection, systemRoot, "online", "在线用户");
        if (sourceOnline != null)
        {
            if (targetOnline == null)
            {
                updateParent(connection, sourceOnline, systemRoot, 8);
            }
            else if (!sourceOnline.equals(targetOnline))
            {
                mergeMenuTree(connection, sourceOnline, targetOnline);
            }
        }

        if (monitorRoot != null) deleteMenuTree(connection, monitorRoot);
    }

    private void mergeMenuTree(Connection connection, Long source, Long target) throws Exception
    {
        grantRoles(connection, source, target);
        for (Long sourceChild : childIds(connection, source))
        {
            Long targetChild = findMatchingChild(connection, target, sourceChild);
            if (targetChild == null)
            {
                updateParent(connection, sourceChild, target, nextOrder(connection, target));
            }
            else
            {
                mergeMenuTree(connection, sourceChild, targetChild);
            }
        }
        deleteMenuNode(connection, source);
    }

    private Long findRoot(Connection connection, String path, String name) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=0 AND (path=? OR menu_name=?) ORDER BY menu_id LIMIT 1"))
        {
            statement.setString(1, path);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private Long findChild(Connection connection, Long parentId, String path, String name) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND (path=? OR menu_name=?) ORDER BY menu_id LIMIT 1"))
        {
            statement.setLong(1, parentId);
            statement.setString(2, path);
            statement.setString(3, name);
            try (ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private Long findMatchingChild(Connection connection, Long targetParent, Long sourceChild) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT target.menu_id
            FROM sys_menu source
            JOIN sys_menu target ON target.parent_id=?
              AND target.menu_type=source.menu_type
              AND ((source.perms IS NOT NULL AND source.perms<>'' AND target.perms=source.perms)
                OR (source.path IS NOT NULL AND source.path<>'' AND target.path=source.path))
            WHERE source.menu_id=?
            ORDER BY target.menu_id
            LIMIT 1
            """))
        {
            statement.setLong(1, targetParent);
            statement.setLong(2, sourceChild);
            try (ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private void updateParent(Connection connection, Long menuId, Long parentId, int order) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE sys_menu SET parent_id=?, order_num=? WHERE menu_id=?"))
        {
            statement.setLong(1, parentId);
            statement.setInt(2, order);
            statement.setLong(3, menuId);
            statement.executeUpdate();
        }
    }

    private int nextOrder(Connection connection, Long parentId) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COALESCE(MAX(order_num),0)+1 FROM sys_menu WHERE parent_id=?"))
        {
            statement.setLong(1, parentId);
            try (ResultSet rows = statement.executeQuery())
            {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void grantRoles(Connection connection, Long source, Long target) throws Exception
    {
        if (!tableExists(connection, "sys_role_menu")) return;
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT role_id,? FROM sys_role_menu WHERE menu_id=?"))
        {
            statement.setLong(1, target);
            statement.setLong(2, source);
            statement.executeUpdate();
        }
    }

    private List<Long> childIds(Connection connection, Long parentId) throws Exception
    {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT menu_id FROM sys_menu WHERE parent_id=?"))
        {
            statement.setLong(1, parentId);
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    private void deleteMenuTree(Connection connection, Long menuId) throws Exception
    {
        for (Long child : childIds(connection, menuId)) deleteMenuTree(connection, child);
        deleteMenuNode(connection, menuId);
    }

    private void deleteMenuNode(Connection connection, Long menuId) throws Exception
    {
        if (tableExists(connection, "sys_role_menu"))
        {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sys_role_menu WHERE menu_id=?"))
            {
                statement.setLong(1, menuId);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sys_menu WHERE menu_id=?"))
        {
            statement.setLong(1, menuId);
            statement.executeUpdate();
        }
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
