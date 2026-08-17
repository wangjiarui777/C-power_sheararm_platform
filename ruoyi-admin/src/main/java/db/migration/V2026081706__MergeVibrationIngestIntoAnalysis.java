package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Merges the vibration intake ledger permissions into the vibration workbench. */
public class V2026081706__MergeVibrationIngestIntoAnalysis extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        if (!tableExists(connection, "sys_menu")) return;
        Long monitoringRoot = findMenu(connection, 0L, "monitoring-center", "M");
        if (monitoringRoot == null) return;
        Long vibrationMenu = findMenu(connection, monitoringRoot, "vibration", "C");
        if (vibrationMenu == null) return;

        Long listPermission = ensurePermission(connection, vibrationMenu, 6, "文件台账查询", "sensor:ingest:list");
        Long associatePermission = ensurePermission(connection, vibrationMenu, 7, "文件关联", "sensor:ingest:associate");
        Long retryPermission = ensurePermission(connection, vibrationMenu, 8, "失败重试", "sensor:ingest:retry");

        Long filesMenu = findMenu(connection, monitoringRoot, "files", "C");
        if (filesMenu == null) return;
        grantRoles(connection, filesMenu, vibrationMenu);
        grantRoles(connection, filesMenu, listPermission);
        grantRolesFromPermission(connection, filesMenu, associatePermission, "sensor:ingest:associate");
        grantRolesFromPermission(connection, filesMenu, retryPermission, "sensor:ingest:retry");
        for (Long child : childIds(connection, filesMenu))
        {
            grantRoles(connection, child, vibrationMenu);
            Long target = permissionTarget(child, associatePermission, retryPermission, connection);
            if (target != null) grantRoles(connection, child, target);
        }
        deleteMenuTree(connection, filesMenu);
    }

    private Long permissionTarget(Long child, Long associate, Long retry, Connection connection) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("SELECT perms FROM sys_menu WHERE menu_id=?"))
        {
            statement.setLong(1, child);
            try (ResultSet rows = statement.executeQuery())
            {
                if (!rows.next()) return null;
                String perms = rows.getString(1);
                if ("sensor:ingest:associate".equals(perms)) return associate;
                if ("sensor:ingest:retry".equals(perms)) return retry;
                return null;
            }
        }
    }

    private void grantRolesFromPermission(Connection connection, Long source, Long target, String permission) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
            SELECT rm.role_id,? FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
            WHERE rm.menu_id=? OR m.perms=?
            """))
        {
            statement.setLong(1, target);
            statement.setLong(2, source);
            statement.setString(3, permission);
            statement.executeUpdate();
        }
    }

    private Long ensurePermission(Connection connection, Long parent, int order, String name, String permission) throws Exception
    {
        Long id = findPermission(connection, parent, permission);
        if (id != null) return id;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,
              is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
            VALUES(?,?,?,'#','','','',1,0,'F','0','0',?,'#','admin',NOW(),'振动分析工作台文件权限')
            """, Statement.RETURN_GENERATED_KEYS))
        {
            statement.setString(1, name);
            statement.setLong(2, parent);
            statement.setInt(3, order);
            statement.setString(4, permission);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys())
            {
                if (!keys.next()) throw new IllegalStateException("无法创建权限: " + permission);
                return keys.getLong(1);
            }
        }
    }

    private void deleteMenuTree(Connection connection, Long menuId) throws Exception
    {
        for (Long child : childIds(connection, menuId)) deleteMenuTree(connection, child);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sys_role_menu WHERE menu_id=?"))
        {
            statement.setLong(1, menuId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sys_menu WHERE menu_id=?"))
        {
            statement.setLong(1, menuId);
            statement.executeUpdate();
        }
    }

    private void grantRoles(Connection connection, Long source, Long target) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
            SELECT role_id,? FROM sys_role_menu WHERE menu_id=?
            """))
        {
            statement.setLong(1, target);
            statement.setLong(2, source);
            statement.executeUpdate();
        }
    }

    private List<Long> childIds(Connection connection, Long parent) throws Exception
    {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT menu_id FROM sys_menu WHERE parent_id=?"))
        {
            statement.setLong(1, parent);
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    private Long findPermission(Connection connection, Long parent, String permission) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("SELECT menu_id FROM sys_menu WHERE parent_id=? AND menu_type='F' AND perms=? LIMIT 1"))
        {
            statement.setLong(1, parent);
            statement.setString(2, permission);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }

    private Long findMenu(Connection connection, Long parent, String path, String menuType) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("SELECT menu_id FROM sys_menu WHERE parent_id=? AND path=? AND menu_type=? LIMIT 1"))
        {
            statement.setLong(1, parent);
            statement.setString(2, path);
            statement.setString(3, menuType);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception
    {
        try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, tableName, new String[] {"TABLE"}))
        {
            return rows.next();
        }
    }
}
