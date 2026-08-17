package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Adds the industrial intake pages to their business-owned navigation roots
 * and creates their RuoYi function permissions.
 * Existing role grants are intentionally left untouched: new capabilities must
 * be selected explicitly from the role menu tree.
 */
public class V2026081502__IndustrialPipelineMenus extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        long monitoringRoot = ensureMenu(connection, 0L, "监测与数据", 4, "monitoring-center", null,
            "MonitoringCenter", "M", "", "chart", "工业监测动态菜单");
        long phmRoot = ensureMenu(connection, 0L, "PHM中心", 6, "phm", null,
            "PhmCenter", "M", "", "monitor", "PHM动态菜单");

        long channelMenu = ensureMenu(connection, phmRoot, "测点接入", 2, "points",
            "sensor/access/points", "SensorAccessPoints", "C", "sensor:channel:list",
            "tree", "设备测点与采集通道绑定");
        ensurePermission(connection, channelMenu, 1, "通道新增", "sensor:channel:add");
        ensurePermission(connection, channelMenu, 2, "通道修改", "sensor:channel:edit");
        ensurePermission(connection, channelMenu, 3, "通道删除", "sensor:channel:remove");

        long ingestMenu = ensureMenu(connection, monitoringRoot, "振动文件接收", 6, "files",
            "sensor/ingest/files", "SensorIngestFiles", "C", "sensor:ingest:list",
            "upload", "统一振动文件接收台账");
        ensurePermission(connection, ingestMenu, 1, "文件关联", "sensor:ingest:associate");
        ensurePermission(connection, ingestMenu, 2, "失败重试", "sensor:ingest:retry");

        Long lowCodeMenu = findMenu(connection, null, "lowcode", "C");
        if (lowCodeMenu != null)
        {
            ensurePermission(connection, lowCodeMenu, 11, "管道试运行", "tool:lowcode:test");
            ensurePermission(connection, lowCodeMenu, 12, "管道启停", "tool:lowcode:activate");
        }
    }

    private long ensureMenu(Connection connection, long parentId, String name, int orderNum,
        String path, String component, String routeName, String menuType, String permission,
        String icon, String remark) throws Exception
    {
        Long menuId = findMenu(connection, parentId, path, menuType);
        if (menuId == null)
        {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,
                  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
                VALUES(?,?,?,?,?,'',?,1,0,?,'0','0',?,?,'admin',NOW(),?)
                """, Statement.RETURN_GENERATED_KEYS))
            {
                statement.setString(1, name);
                statement.setLong(2, parentId);
                statement.setInt(3, orderNum);
                statement.setString(4, path);
                statement.setString(5, component);
                statement.setString(6, routeName);
                statement.setString(7, menuType);
                statement.setString(8, permission);
                statement.setString(9, icon);
                statement.setString(10, remark);
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
            mergeDuplicateMenus(connection, menuId, parentId, path, menuType);
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE sys_menu SET menu_name=?,order_num=?,component=?,route_name=?,is_frame=1,
                  is_cache=0,visible='0',status='0',perms=?,icon=?,remark=?,update_by='admin',
                  update_time=NOW() WHERE menu_id=?
                """))
            {
                statement.setString(1, name);
                statement.setInt(2, orderNum);
                statement.setString(3, component);
                statement.setString(4, routeName);
                statement.setString(5, permission);
                statement.setString(6, icon);
                statement.setString(7, remark);
                statement.setLong(8, menuId);
                statement.executeUpdate();
            }
        }
        return menuId;
    }

    private void ensurePermission(Connection connection, long parentId, int orderNum,
        String name, String permission) throws Exception
    {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND menu_type='F' AND perms=? ORDER BY menu_id LIMIT 1"))
        {
            query.setLong(1, parentId);
            query.setString(2, permission);
            try (ResultSet result = query.executeQuery())
            {
                if (result.next())
                {
                    long menuId = result.getLong(1);
                    mergeDuplicatePermissions(connection, menuId, parentId, permission);
                    try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE sys_menu SET menu_name=?,order_num=?,path='#',component='',visible='0',status='0',icon='#',update_by='admin',update_time=NOW() WHERE menu_id=?"))
                    {
                        update.setString(1, name);
                        update.setInt(2, orderNum);
                        update.setLong(3, menuId);
                        update.executeUpdate();
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,
              is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
            VALUES(?,?,?,'#','','','',1,0,'F','0','0',?,'#','admin',NOW(),'业务操作权限')
            """))
        {
            statement.setString(1, name);
            statement.setLong(2, parentId);
            statement.setInt(3, orderNum);
            statement.setString(4, permission);
            statement.executeUpdate();
        }
    }

    /** Keep the oldest deterministic row and transfer existing role grants before cleanup. */
    private void mergeDuplicateMenus(Connection connection, long keepId, long parentId,
        String path, String menuType) throws Exception
    {
        mergeDuplicates(connection, keepId,
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND path=? AND menu_type=? AND menu_id<>?",
            statement -> {
                statement.setLong(1, parentId);
                statement.setString(2, path);
                statement.setString(3, menuType);
                statement.setLong(4, keepId);
            });
    }

    private void mergeDuplicatePermissions(Connection connection, long keepId, long parentId,
        String permission) throws Exception
    {
        mergeDuplicates(connection, keepId,
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND menu_type='F' AND perms=? AND menu_id<>?",
            statement -> {
                statement.setLong(1, parentId);
                statement.setString(2, permission);
                statement.setLong(3, keepId);
            });
    }

    private void mergeDuplicates(Connection connection, long keepId, String sql,
        SqlBinder binder) throws Exception
    {
        try (PreparedStatement query = connection.prepareStatement(sql))
        {
            binder.bind(query);
            try (ResultSet result = query.executeQuery())
            {
                while (result.next())
                {
                    long duplicateId = result.getLong(1);
                    try (PreparedStatement transfer = connection.prepareStatement(
                        "INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT role_id,? FROM sys_role_menu WHERE menu_id=?"))
                    {
                        transfer.setLong(1, keepId);
                        transfer.setLong(2, duplicateId);
                        transfer.executeUpdate();
                    }
                    try (PreparedStatement deleteRelations = connection.prepareStatement(
                        "DELETE FROM sys_role_menu WHERE menu_id=?"))
                    {
                        deleteRelations.setLong(1, duplicateId);
                        deleteRelations.executeUpdate();
                    }
                    try (PreparedStatement reparent = connection.prepareStatement(
                        "UPDATE sys_menu SET parent_id=? WHERE parent_id=?"))
                    {
                        reparent.setLong(1, keepId);
                        reparent.setLong(2, duplicateId);
                        reparent.executeUpdate();
                    }
                    try (PreparedStatement deleteMenu = connection.prepareStatement(
                        "DELETE FROM sys_menu WHERE menu_id=?"))
                    {
                        deleteMenu.setLong(1, duplicateId);
                        deleteMenu.executeUpdate();
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface SqlBinder
    {
        void bind(PreparedStatement statement) throws Exception;
    }

    private Long findMenu(Connection connection, Long parentId, String path, String menuType)
        throws Exception
    {
        String sql = parentId == null
            ? "SELECT menu_id FROM sys_menu WHERE path=? AND menu_type=? ORDER BY menu_id LIMIT 1"
            : "SELECT menu_id FROM sys_menu WHERE parent_id=? AND path=? AND menu_type=? ORDER BY menu_id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            int index = 1;
            if (parentId != null) statement.setLong(index++, parentId);
            statement.setString(index++, path);
            statement.setString(index, menuType);
            try (ResultSet result = statement.executeQuery())
            {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }
}
