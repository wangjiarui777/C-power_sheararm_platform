package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds the history data download menu, permissions and supporting indexes. */
public class V2026072301__HistoryDataDownload extends BaseJavaMigration
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
        requireTable(connection, "sys_menu");
        requireTable(connection, "sys_role_menu");
        requireTable(connection, "device_vibration_data");
        requireTable(connection, "enhanced_inference_record");

        Set<Long> viewerRoles = rolesWithPermission(connection, "sensor:vibration:list");
        Set<Long> exportRoles = rolesWithPermission(connection, "sensor:vibration:export");

        long parentId = ensureMonitoringParent(connection);
        long pageId = ensurePageMenu(connection, parentId);
        long exportId = ensureExportPermission(connection, pageId);

        grantMenu(connection, viewerRoles, parentId);
        grantMenu(connection, viewerRoles, pageId);
        grantMenu(connection, exportRoles, parentId);
        grantMenu(connection, exportRoles, pageId);
        grantMenu(connection, exportRoles, exportId);

        ensureIndex(connection, "device_vibration_data", "idx_history_device_point_time",
            "ALTER TABLE device_vibration_data ADD INDEX idx_history_device_point_time (device_code, point_id, sample_time)");
        ensureIndex(connection, "enhanced_inference_record", "idx_device_point_time",
            "ALTER TABLE enhanced_inference_record ADD INDEX idx_device_point_time (device_code, point_id, create_time)");
    }

    private long ensureMonitoringParent(Connection connection) throws SQLException
    {
        Long existing = findMenu(connection, 0L, "monitoring-center", null);
        if (existing != null)
        {
            return existing;
        }
        return insertMenu(connection, "监测与数据", 0L, 4, "monitoring-center", null,
            "MonitoringCenter", "M", "", "chart", "工业监测动态菜单");
    }

    private long ensurePageMenu(Connection connection, long parentId) throws SQLException
    {
        Long existing = findMenu(connection, parentId, "history-data", null);
        if (existing != null)
        {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE sys_menu SET menu_name=?, order_num=?, component=?, route_name=?, perms=?, icon=?, status='0', visible='0' WHERE menu_id=?"))
            {
                statement.setString(1, "历史数据下载");
                statement.setInt(2, 4);
                statement.setString(3, "monitoring-center/history-data/index");
                statement.setString(4, "HistoryDataDownload");
                statement.setString(5, "sensor:history:list");
                statement.setString(6, "download");
                statement.setLong(7, existing);
                statement.executeUpdate();
            }
            return existing;
        }
        return insertMenu(connection, "历史数据下载", parentId, 4, "history-data",
            "monitoring-center/history-data/index", "HistoryDataDownload", "C",
            "sensor:history:list", "download", "振动与诊断历史数据查询下载");
    }

    private long ensureExportPermission(Connection connection, long pageId) throws SQLException
    {
        Long existing = findMenu(connection, pageId, "#", "sensor:history:export");
        if (existing != null)
        {
            return existing;
        }
        return insertMenu(connection, "历史数据导出", pageId, 1, "#", "", "", "F",
            "sensor:history:export", "#", "历史数据CSV导出权限");
    }

    private Long findMenu(Connection connection, long parentId, String path, String permission) throws SQLException
    {
        String sql = permission == null
            ? "SELECT menu_id FROM sys_menu WHERE parent_id=? AND path=? ORDER BY menu_id LIMIT 1"
            : "SELECT menu_id FROM sys_menu WHERE parent_id=? AND path=? AND perms=? ORDER BY menu_id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, parentId);
            statement.setString(2, path);
            if (permission != null)
            {
                statement.setString(3, permission);
            }
            try (ResultSet result = statement.executeQuery())
            {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private long insertMenu(Connection connection, String name, long parentId, int orderNum,
                            String path, String component, String routeName, String type,
                            String permission, String icon, String remark) throws SQLException
    {
        String sql = "INSERT INTO sys_menu "
            + "(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) "
            + "VALUES (?,?,?,?,?,'',?,1,0,?,'0','0',?,?,'admin',CURRENT_TIMESTAMP,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
        {
            statement.setString(1, name);
            statement.setLong(2, parentId);
            statement.setInt(3, orderNum);
            statement.setString(4, path);
            statement.setString(5, component);
            statement.setString(6, routeName);
            statement.setString(7, type);
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
        throw new FlywayException("Failed to create history data menu: " + name);
    }

    private Set<Long> rolesWithPermission(Connection connection, String permission) throws SQLException
    {
        Set<Long> roles = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT DISTINCT role_menu.role_id FROM sys_role_menu role_menu "
                + "JOIN sys_menu menu ON menu.menu_id=role_menu.menu_id WHERE menu.perms=?"))
        {
            statement.setString(1, permission);
            try (ResultSet result = statement.executeQuery())
            {
                while (result.next())
                {
                    roles.add(result.getLong(1));
                }
            }
        }
        return roles;
    }

    private void grantMenu(Connection connection, Set<Long> roles, long menuId) throws SQLException
    {
        try (PreparedStatement exists = connection.prepareStatement(
                 "SELECT 1 FROM sys_role_menu WHERE role_id=? AND menu_id=?");
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)"))
        {
            for (Long roleId : roles)
            {
                exists.setLong(1, roleId);
                exists.setLong(2, menuId);
                try (ResultSet result = exists.executeQuery())
                {
                    if (result.next())
                    {
                        continue;
                    }
                }
                insert.setLong(1, roleId);
                insert.setLong(2, menuId);
                insert.addBatch();
            }
            if (!roles.isEmpty())
            {
                insert.executeBatch();
            }
        }
    }

    private void ensureIndex(Connection connection, String table, String index, String ddl) throws SQLException
    {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet indexes = meta.getIndexInfo(connection.getCatalog(), null, table, false, false))
        {
            while (indexes.next())
            {
                if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME")))
                {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement())
        {
            statement.executeUpdate(ddl);
        }
    }

    private void requireTable(Connection connection, String table) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getTables(
            connection.getCatalog(), null, table, new String[] {"TABLE"}))
        {
            if (!result.next())
            {
                throw new FlywayException("Required table is missing: " + table);
            }
        }
    }
}
