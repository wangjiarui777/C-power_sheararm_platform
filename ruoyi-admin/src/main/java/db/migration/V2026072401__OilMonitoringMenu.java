package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Adds the frontend-only oil monitoring entry beneath the existing monitoring
 * menu. The migration intentionally creates no oil telemetry tables.
 */
public class V2026072401__OilMonitoringMenu extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        long parentId = findMenu(connection, 0L, "monitoring-center");
        if (parentId == 0L)
        {
            parentId = insertMenu(connection, "监测与数据", 0L, 4, "monitoring-center",
                null, "MonitoringCenter", "M", "", "chart", "工业监测动态菜单");
        }

        long oilMenuId = findMenu(connection, parentId, "oil");
        if (oilMenuId == 0L)
        {
            oilMenuId = insertMenu(connection, "油液监测", parentId, 2, "oil",
                "monitoring-center/oil/index", "OilMonitoring", "C",
                "sensor:monitoring:view", "monitor", "在线油液监测预留页面");
        }
        else
        {
            updateOilMenu(connection, oilMenuId);
        }

        normalizeMonitoringOrder(connection, parentId);
        inheritMonitoringRoles(connection, parentId, oilMenuId);
    }

    private long findMenu(Connection connection, long parentId, String path) throws Exception
    {
        String sql = "SELECT menu_id FROM sys_menu WHERE parent_id = ? AND path = ? ORDER BY menu_id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, parentId);
            statement.setString(2, path);
            try (ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private long insertMenu(Connection connection, String name, long parentId, int orderNum,
        String path, String component, String routeName, String menuType, String permission,
        String icon, String remark) throws Exception
    {
        String sql = """
            INSERT INTO sys_menu
              (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
               is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
            VALUES (?, ?, ?, ?, ?, '', ?, 1, 0, ?, '0', '0', ?, ?, 'admin', NOW(), ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
        {
            statement.setString(1, name);
            statement.setLong(2, parentId);
            statement.setInt(3, orderNum);
            statement.setString(4, path);
            if (component == null)
            {
                statement.setNull(5, Types.VARCHAR);
            }
            else
            {
                statement.setString(5, component);
            }
            statement.setString(6, routeName);
            statement.setString(7, menuType);
            statement.setString(8, permission);
            statement.setString(9, icon);
            statement.setString(10, remark);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys())
            {
                if (keys.next())
                {
                    return keys.getLong(1);
                }
            }
        }
        return findMenu(connection, parentId, path);
    }

    private void updateOilMenu(Connection connection, long oilMenuId) throws Exception
    {
        String sql = """
            UPDATE sys_menu
               SET menu_name = '油液监测',
                   order_num = 2,
                   component = 'monitoring-center/oil/index',
                   route_name = 'OilMonitoring',
                   menu_type = 'C',
                   visible = '0',
                   status = '0',
                   perms = 'sensor:monitoring:view',
                   icon = 'monitor',
                   remark = '在线油液监测预留页面'
             WHERE menu_id = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, oilMenuId);
            statement.executeUpdate();
        }
    }

    private void normalizeMonitoringOrder(Connection connection, long parentId) throws Exception
    {
        String sql = """
            UPDATE sys_menu
               SET order_num = CASE path
                   WHEN 'index' THEN 1
                   WHEN 'oil' THEN 2
                   WHEN 'vibration' THEN 3
                   WHEN 'temperature' THEN 4
                   ELSE order_num END
             WHERE parent_id = ?
               AND path IN ('index', 'oil', 'vibration', 'temperature')
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, parentId);
            statement.executeUpdate();
        }
    }

    private void inheritMonitoringRoles(Connection connection, long parentId, long oilMenuId) throws Exception
    {
        String sql = """
            INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
            SELECT DISTINCT role_menu.role_id, ?
              FROM sys_role_menu role_menu
              JOIN sys_menu source_menu ON source_menu.menu_id = role_menu.menu_id
             WHERE role_menu.role_id IS NOT NULL
               AND (source_menu.menu_id = ? OR source_menu.parent_id = ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, oilMenuId);
            statement.setLong(2, parentId);
            statement.setLong(3, parentId);
            statement.executeUpdate();
        }
    }
}
