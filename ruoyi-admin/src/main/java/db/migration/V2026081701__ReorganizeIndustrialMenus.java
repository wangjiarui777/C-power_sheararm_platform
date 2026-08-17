package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Rehomes industrial intake pages under the monitoring and PHM menu roots. */
public class V2026081701__ReorganizeIndustrialMenus extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        if (!tableExists(connection, "sys_menu")) return;

        long monitoringRoot = ensureDirectory(connection, "监测与数据", 4, "monitoring-center",
            "MonitoringCenter", "chart", "工业监测动态菜单");
        long phmRoot = ensureDirectory(connection, "PHM中心", 6, "phm",
            "PhmCenter", "monitor", "PHM动态菜单");
        Long legacyRoot = findMenu(connection, 0L, "sensor-access", "M");

        long pointsMenu = relocatePage(connection, legacyRoot, phmRoot, "测点接入", 2, "points",
            "sensor/access/points", "SensorAccessPoints", "sensor:channel:list", "tree",
            "设备测点与采集通道绑定");
        ensurePermission(connection, pointsMenu, 1, "通道新增", "sensor:channel:add");
        ensurePermission(connection, pointsMenu, 2, "通道修改", "sensor:channel:edit");
        ensurePermission(connection, pointsMenu, 3, "通道删除", "sensor:channel:remove");

        long filesMenu = relocatePage(connection, legacyRoot, monitoringRoot, "振动文件接收", 6, "files",
            "sensor/ingest/files", "SensorIngestFiles", "sensor:ingest:list", "upload",
            "统一振动文件接收台账");
        ensurePermission(connection, filesMenu, 1, "文件关联", "sensor:ingest:associate");
        ensurePermission(connection, filesMenu, 2, "失败重试", "sensor:ingest:retry");

        if (legacyRoot != null)
        {
            grantParentFromMenu(connection, legacyRoot, monitoringRoot);
            grantParentFromMenu(connection, legacyRoot, phmRoot);
            deleteLegacyRoot(connection, legacyRoot);
        }

        normalizeMonitoringOrder(connection, monitoringRoot);
        normalizePhmOrder(connection, phmRoot);
    }

    private long ensureDirectory(Connection connection, String name, int order, String path,
        String routeName, String icon, String remark) throws Exception
    {
        Long menuId = findMenu(connection, 0L, path, "M");
        if (menuId == null)
        {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,
                  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
                VALUES(?,0,?,?,NULL,'',?,1,0,'M','0','0','',?,'admin',NOW(),?)
                """, Statement.RETURN_GENERATED_KEYS))
            {
                statement.setString(1, name);
                statement.setInt(2, order);
                statement.setString(3, path);
                statement.setString(4, routeName);
                statement.setString(5, icon);
                statement.setString(6, remark);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys())
                {
                    if (!keys.next()) throw new IllegalStateException("无法创建菜单: " + path);
                    menuId = keys.getLong(1);
                }
            }
        }
        else
        {
            mergeDuplicateMenus(connection, menuId, 0L, path, "M");
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE sys_menu SET menu_name=?,order_num=?,route_name=?,visible='0',status='0',
                  perms='',icon=?,remark=?,update_by='admin',update_time=NOW() WHERE menu_id=?
                """))
            {
                statement.setString(1, name);
                statement.setInt(2, order);
                statement.setString(3, routeName);
                statement.setString(4, icon);
                statement.setString(5, remark);
                statement.setLong(6, menuId);
                statement.executeUpdate();
            }
        }
        return menuId;
    }

    private long relocatePage(Connection connection, Long legacyRoot, long targetRoot, String name,
        int order, String path, String component, String routeName, String permission, String icon,
        String remark) throws Exception
    {
        Long target = findMenu(connection, targetRoot, path, "C");
        if (target != null) mergeDuplicateMenus(connection, target, targetRoot, path, "C");
        List<Long> sources = findLegacyPages(connection, legacyRoot, path, component, permission);
        if (target == null && !sources.isEmpty())
        {
            target = sources.remove(0);
            updatePage(connection, target, targetRoot, name, order, path, component, routeName,
                permission, icon, remark);
        }
        else if (target == null)
        {
            target = insertPage(connection, targetRoot, name, order, path, component, routeName,
                permission, icon, remark);
        }

        grantParentFromMenu(connection, target, targetRoot);
        for (Long source : sources)
        {
            mergeMenu(connection, target, source);
        }
        return target;
    }

    private List<Long> findLegacyPages(Connection connection, Long legacyRoot, String path,
        String component, String permission) throws Exception
    {
        List<Long> ids = new ArrayList<>();
        if (legacyRoot == null) return ids;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT menu_id FROM sys_menu
            WHERE parent_id=? AND menu_type='C'
              AND (path=? OR component=? OR perms=? )
            ORDER BY menu_id
            """))
        {
            statement.setLong(1, legacyRoot);
            statement.setString(2, path);
            statement.setString(3, component);
            statement.setString(4, permission);
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    private long insertPage(Connection connection, long parentId, String name, int order, String path,
        String component, String routeName, String permission, String icon, String remark) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,
              is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
            VALUES(?,?,?,?,?,'',?,1,0,'C','0','0',?,?,'admin',NOW(),?)
            """, Statement.RETURN_GENERATED_KEYS))
        {
            statement.setString(1, name);
            statement.setLong(2, parentId);
            statement.setInt(3, order);
            statement.setString(4, path);
            statement.setString(5, component);
            statement.setString(6, routeName);
            statement.setString(7, permission);
            statement.setString(8, icon);
            statement.setString(9, remark);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys())
            {
                if (!keys.next()) throw new IllegalStateException("无法创建菜单: " + path);
                return keys.getLong(1);
            }
        }
    }

    private void updatePage(Connection connection, long menuId, long parentId, String name, int order,
        String path, String component, String routeName, String permission, String icon,
        String remark) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE sys_menu SET menu_name=?,parent_id=?,order_num=?,path=?,component=?,route_name=?,
              is_frame=1,is_cache=0,menu_type='C',visible='0',status='0',perms=?,icon=?,remark=?,
              update_by='admin',update_time=NOW() WHERE menu_id=?
            """))
        {
            statement.setString(1, name);
            statement.setLong(2, parentId);
            statement.setInt(3, order);
            statement.setString(4, path);
            statement.setString(5, component);
            statement.setString(6, routeName);
            statement.setString(7, permission);
            statement.setString(8, icon);
            statement.setString(9, remark);
            statement.setLong(10, menuId);
            statement.executeUpdate();
        }
    }

    private void ensurePermission(Connection connection, long parentId, int order, String name,
        String permission) throws Exception
    {
        Long menuId = findPermission(connection, parentId, permission);
        if (menuId == null)
        {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,
                  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
                VALUES(?,?,?,'#','','','',1,0,'F','0','0',?,'#','admin',NOW(),'业务操作权限')
                """))
            {
                statement.setString(1, name);
                statement.setLong(2, parentId);
                statement.setInt(3, order);
                statement.setString(4, permission);
                statement.executeUpdate();
            }
            return;
        }
        mergeDuplicatePermissions(connection, menuId, parentId, permission);
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE sys_menu SET menu_name=?,order_num=?,path='#',visible='0',status='0',icon='#',update_by='admin',update_time=NOW() WHERE menu_id=?"))
        {
            statement.setString(1, name);
            statement.setInt(2, order);
            statement.setLong(3, menuId);
            statement.executeUpdate();
        }
    }

    private void mergeMenu(Connection connection, long keepId, long duplicateId) throws Exception
    {
        if (keepId == duplicateId) return;
        transferRoles(connection, duplicateId, keepId);
        for (Long childId : childIds(connection, duplicateId))
        {
            Long matchingChild = findEquivalentChild(connection, keepId, childId);
            if (matchingChild == null)
            {
                updateParent(connection, childId, keepId);
            }
            else
            {
                mergeMenu(connection, matchingChild, childId);
            }
        }
        deleteRoleLinks(connection, duplicateId);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sys_menu WHERE menu_id=?"))
        {
            statement.setLong(1, duplicateId);
            statement.executeUpdate();
        }
    }

    private void mergeDuplicateMenus(Connection connection, long keepId, long parentId, String path,
        String menuType) throws Exception
    {
        List<Long> duplicates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND path=? AND menu_type=? AND menu_id<>? ORDER BY menu_id"))
        {
            statement.setLong(1, parentId);
            statement.setString(2, path);
            statement.setString(3, menuType);
            statement.setLong(4, keepId);
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next()) duplicates.add(rows.getLong(1));
            }
        }
        for (Long duplicate : duplicates) mergeMenu(connection, keepId, duplicate);
    }

    private void mergeDuplicatePermissions(Connection connection, long keepId, long parentId,
        String permission) throws Exception
    {
        List<Long> duplicates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND menu_type='F' AND perms=? AND menu_id<>? ORDER BY menu_id"))
        {
            statement.setLong(1, parentId);
            statement.setString(2, permission);
            statement.setLong(3, keepId);
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next()) duplicates.add(rows.getLong(1));
            }
        }
        for (Long duplicate : duplicates) mergeMenu(connection, keepId, duplicate);
    }

    private Long findEquivalentChild(Connection connection, long parentId, long childId) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT candidate.menu_id FROM sys_menu candidate
            JOIN sys_menu source ON source.menu_id=?
            WHERE candidate.parent_id=? AND candidate.menu_type=source.menu_type
              AND ((candidate.menu_type='F' AND candidate.perms=source.perms)
                OR (candidate.menu_type<>'F' AND candidate.path=source.path))
            ORDER BY candidate.menu_id LIMIT 1
            """))
        {
            statement.setLong(1, childId);
            statement.setLong(2, parentId);
            try (ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private List<Long> childIds(Connection connection, long parentId) throws Exception
    {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? ORDER BY menu_id"))
        {
            statement.setLong(1, parentId);
            try (ResultSet rows = statement.executeQuery())
            {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    private void deleteLegacyRoot(Connection connection, long legacyRoot) throws Exception
    {
        if (!childIds(connection, legacyRoot).isEmpty())
        {
            throw new IllegalStateException("数据接入目录仍包含未迁移菜单，已停止删除以避免丢失菜单配置");
        }
        deleteRoleLinks(connection, legacyRoot);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sys_menu WHERE menu_id=?"))
        {
            statement.setLong(1, legacyRoot);
            statement.executeUpdate();
        }
    }

    private void grantParentFromMenu(Connection connection, long sourceMenuId, long targetParentId)
        throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
            SELECT role_id,? FROM sys_role_menu WHERE menu_id=?
            """))
        {
            statement.setLong(1, targetParentId);
            statement.setLong(2, sourceMenuId);
            statement.executeUpdate();
        }
    }

    private void transferRoles(Connection connection, long sourceMenuId, long targetMenuId) throws Exception
    {
        grantParentFromMenu(connection, sourceMenuId, targetMenuId);
        deleteRoleLinks(connection, sourceMenuId);
    }

    private void deleteRoleLinks(Connection connection, long menuId) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM sys_role_menu WHERE menu_id=?"))
        {
            statement.setLong(1, menuId);
            statement.executeUpdate();
        }
    }

    private void updateParent(Connection connection, long menuId, long parentId) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE sys_menu SET parent_id=?,update_by='admin',update_time=NOW() WHERE menu_id=?"))
        {
            statement.setLong(1, parentId);
            statement.setLong(2, menuId);
            statement.executeUpdate();
        }
    }

    private void normalizeMonitoringOrder(Connection connection, long parentId) throws Exception
    {
        updateOrders(connection, parentId, new String[][] {
            {"index", "1"}, {"oil", "2"}, {"vibration", "3"}, {"temperature", "4"},
            {"history-data", "5"}, {"files", "6"}
        });
    }

    private void normalizePhmOrder(Connection connection, long parentId) throws Exception
    {
        updateOrders(connection, parentId, new String[][] {
            {"cluster", "1"}, {"points", "2"}, {"brain", "3"}, {"alarms", "4"},
            {"events", "5"}, {"reports", "6"}, {"config", "7"}
        });
    }

    private void updateOrders(Connection connection, long parentId, String[][] orders) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE sys_menu SET order_num=? WHERE parent_id=? AND path=?"))
        {
            for (String[] order : orders)
            {
                statement.setInt(1, Integer.parseInt(order[1]));
                statement.setLong(2, parentId);
                statement.setString(3, order[0]);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Long findPermission(Connection connection, long parentId, String permission) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND menu_type='F' AND perms=? ORDER BY menu_id LIMIT 1"))
        {
            statement.setLong(1, parentId);
            statement.setString(2, permission);
            try (ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private Long findMenu(Connection connection, long parentId, String path, String menuType) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND path=? AND menu_type=? ORDER BY menu_id LIMIT 1"))
        {
            statement.setLong(1, parentId);
            statement.setString(2, path);
            statement.setString(3, menuType);
            try (ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private Long findMenu(Connection connection, Long parentId, String path, String menuType) throws Exception
    {
        if (parentId != null) return findMenu(connection, parentId.longValue(), path, menuType);
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE path=? AND menu_type=? ORDER BY menu_id LIMIT 1"))
        {
            statement.setString(1, path);
            statement.setString(2, menuType);
            try (ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
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
