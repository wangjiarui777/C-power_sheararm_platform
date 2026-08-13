package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds the directly accessible PHM machine-brain page to RuoYi dynamic menus. */
public class V2026071601__AddPhmBrainDynamicMenu extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        Long phmParentId = findMenuId(connection, 0L, "phm");
        if (phmParentId == null)
        {
            throw new SQLException("PHM中心菜单不存在，无法创建机器大脑菜单");
        }

        Long brainMenuId = findMenuId(connection, phmParentId, "brain");
        if (brainMenuId == null)
        {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sys_menu
                  (menu_name, parent_id, order_num, path, component, query, route_name,
                   is_frame, is_cache, menu_type, visible, status, perms, icon,
                   create_by, create_time, remark)
                VALUES
                  ('机器大脑', ?, 2, 'brain', 'phm/brain/index', '', 'PhmBrain',
                   1, 0, 'C', '0', '0', 'phm:device:query', 'component',
                   'admin', NOW(), 'PHM设备级健康与诊断工作台')
                """, Statement.RETURN_GENERATED_KEYS))
            {
                statement.setLong(1, phmParentId);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys())
                {
                    if (keys.next())
                    {
                        brainMenuId = keys.getLong(1);
                    }
                }
            }
        }

        if (brainMenuId == null)
        {
            brainMenuId = findMenuId(connection, phmParentId, "brain");
        }
        reorderPhmMenus(connection, phmParentId);
        inheritRoleAssignments(connection, phmParentId, brainMenuId);
    }

    private Long findMenuId(Connection connection, Long parentId, String path) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id = ? AND path = ? ORDER BY menu_id LIMIT 1"))
        {
            statement.setLong(1, parentId);
            statement.setString(2, path);
            try (ResultSet result = statement.executeQuery())
            {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private void reorderPhmMenus(Connection connection, Long parentId) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE sys_menu
            SET order_num = CASE path
              WHEN 'cluster' THEN 1 WHEN 'brain' THEN 2 WHEN 'alarms' THEN 3
              WHEN 'events' THEN 4 WHEN 'reports' THEN 5 WHEN 'config' THEN 6
              ELSE order_num END
            WHERE parent_id = ?
              AND path IN ('cluster','brain','alarms','events','reports','config')
            """))
        {
            statement.setLong(1, parentId);
            statement.executeUpdate();
        }
    }

    private void inheritRoleAssignments(Connection connection, Long parentId, Long brainMenuId) throws SQLException
    {
        if (brainMenuId == null)
        {
            throw new SQLException("机器大脑菜单创建失败");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
            SELECT DISTINCT role_id, ?
            FROM sys_role_menu
            WHERE menu_id IN (
              SELECT menu_id FROM sys_menu
              WHERE menu_id = ? OR (parent_id = ? AND (path = 'cluster' OR perms = 'phm:device:query'))
            )
            """))
        {
            statement.setLong(1, brainMenuId);
            statement.setLong(2, parentId);
            statement.setLong(3, parentId);
            statement.executeUpdate();
        }
    }
}
