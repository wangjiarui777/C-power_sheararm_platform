package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Replaces department data scope with direct user-to-device authorization. */
public class V2026081704__UserDeviceAuthorization extends BaseJavaMigration
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
        if (!tableExists(connection, "sys_user"))
        {
            return;
        }
        createUserDeviceTable(connection);
        removeDepartmentMenus(connection);
    }

    private void createUserDeviceTable(Connection connection) throws Exception
    {
        try (Statement statement = connection.createStatement())
        {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS sys_user_device (
                  user_id BIGINT NOT NULL COMMENT '用户ID',
                  device_id BIGINT NOT NULL COMMENT 'PHM设备ID',
                  create_by VARCHAR(64) DEFAULT NULL COMMENT '授权人',
                  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
                  PRIMARY KEY (user_id, device_id),
                  KEY idx_sys_user_device_user (user_id),
                  KEY idx_sys_user_device_device (device_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户设备授权表'
                """);
        }
    }

    private void removeDepartmentMenus(Connection connection) throws Exception
    {
        if (!tableExists(connection, "sys_menu"))
        {
            return;
        }
        List<Long> menuIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE path='dept' OR menu_name='部门管理'"))
        {
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next())
                {
                    menuIds.add(rows.getLong(1));
                }
            }
        }
        for (Long menuId : new ArrayList<>(menuIds))
        {
            collectChildren(connection, menuId, menuIds);
        }
        if (menuIds.isEmpty())
        {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(menuIds.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM sys_role_menu WHERE menu_id IN (" + placeholders + ")"))
        {
            bind(statement, menuIds);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM sys_menu WHERE menu_id IN (" + placeholders + ")"))
        {
            bind(statement, menuIds);
            statement.executeUpdate();
        }
    }

    private void collectChildren(Connection connection, long parentId, List<Long> menuIds) throws Exception
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
                collectChildren(connection, childId, menuIds);
            }
        }
    }

    private void bind(PreparedStatement statement, List<Long> ids) throws Exception
    {
        for (int i = 0; i < ids.size(); i++) statement.setLong(i + 1, ids.get(i));
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception
    {
        try (ResultSet rows = connection.getMetaData().getTables(
            connection.getCatalog(), null, tableName, new String[] {"TABLE"}))
        {
            return rows.next();
        }
    }
}
