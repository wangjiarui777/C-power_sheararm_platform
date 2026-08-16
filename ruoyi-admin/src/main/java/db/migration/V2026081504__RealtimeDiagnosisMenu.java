package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds the real-time diagnosis policy page and function permissions. */
public class V2026081504__RealtimeDiagnosisMenu extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        Long parent = find(connection, "analysis-toolkit", "M");
        if (parent == null) return;
        long page = ensureMenu(connection, parent, "实时诊断策略", "realtime-diagnosis",
            "monitor/diagnosis/realtime-policy/index", "RealtimeDiagnosisPolicy",
            "sensor:diagnosis:realtime:list", 2);
        ensurePermission(connection, page, "策略编辑", "sensor:diagnosis:realtime:edit", 1);
    }

    private long ensureMenu(Connection connection, long parent, String name, String path,
        String component, String routeName, String permission, int order) throws Exception
    {
        Long existing = findChild(connection, parent, path, "C");
        if (existing != null) return existing;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,
              is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
            VALUES(?,?,?, ?,?,'',?,1,0,'C','0','0',?,'cpu','admin',NOW(),'实时诊断策略')
            """, Statement.RETURN_GENERATED_KEYS))
        {
            statement.setString(1, name); statement.setLong(2, parent); statement.setInt(3, order);
            statement.setString(4, path); statement.setString(5, component); statement.setString(6, routeName);
            statement.setString(7, permission); statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys())
            {
                if (!keys.next()) throw new IllegalStateException("无法创建实时诊断菜单");
                return keys.getLong(1);
            }
        }
    }

    private void ensurePermission(Connection connection, long parent, String name, String permission,
        int order) throws Exception
    {
        if (findPermission(connection, parent, permission) != null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,
              is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
            VALUES(?,?,?,'#','','','',1,0,'F','0','0',?,'#','admin',NOW(),'业务操作权限')
            """))
        {
            statement.setString(1, name); statement.setLong(2, parent); statement.setInt(3, order);
            statement.setString(4, permission); statement.executeUpdate();
        }
    }

    private Long find(Connection connection, String path, String type) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE path=? AND menu_type=? ORDER BY menu_id LIMIT 1"))
        {
            statement.setString(1, path); statement.setString(2, type);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }

    private Long findChild(Connection connection, long parent, String path, String type) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND path=? AND menu_type=? LIMIT 1"))
        {
            statement.setLong(1, parent); statement.setString(2, path); statement.setString(3, type);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }

    private Long findPermission(Connection connection, long parent, String permission) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND menu_type='F' AND perms=? LIMIT 1"))
        {
            statement.setLong(1, parent); statement.setString(2, permission);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }
}
