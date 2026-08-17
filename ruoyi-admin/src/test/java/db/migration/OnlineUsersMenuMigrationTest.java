package db.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OnlineUsersMenuMigrationTest
{
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ry_online_users_menu").withUsername("test").withPassword("test");

    @Test
    void movesOnlineUsersAndRemovesSystemMonitorRoot() throws Exception
    {
        try (Connection connection = connection(); Statement statement = connection.createStatement())
        {
            statement.execute("""
                CREATE TABLE sys_menu(
                  menu_id BIGINT AUTO_INCREMENT PRIMARY KEY, menu_name VARCHAR(50), parent_id BIGINT,
                  order_num INT, path VARCHAR(200), component VARCHAR(255), query VARCHAR(255),
                  route_name VARCHAR(50), is_frame INT, is_cache INT, menu_type CHAR(1), visible CHAR(1),
                  status CHAR(1), perms VARCHAR(100), icon VARCHAR(100), create_by VARCHAR(64),
                  create_time DATETIME, update_by VARCHAR(64), update_time DATETIME, remark VARCHAR(500))
                """);
            statement.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,UNIQUE KEY(role_id,menu_id))");
            statement.execute("""
                INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,route_name,menu_type,perms)
                VALUES
                  (1,'系统管理',0,1,'system',NULL,'System','M',''),
                  (2,'系统监控',0,2,'monitor',NULL,'Monitor','M',''),
                  (3,'在线用户',2,1,'online','monitor/online/index','Online','C','monitor:online:list'),
                  (4,'在线查询',3,1,'#','','','F','monitor:online:query'),
                  (5,'批量强退',3,2,'#','','','F','monitor:online:batchLogout'),
                  (6,'单条强退',3,3,'#','','','F','monitor:online:forceLogout'),
                  (7,'废弃监控页',2,2,'server','monitor/server/index','Server','C','monitor:server:list')
                """);
            statement.execute("INSERT INTO sys_role_menu VALUES(7,2),(7,3),(7,4),(7,5),(7,6),(7,7)");
        }

        Flyway flyway = Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .baselineOnMigrate(true).baselineVersion("2026081706")
            .javaMigrations(new V2026081707__MergeOnlineUsersIntoSystemManagement()).load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);

        try (Connection connection = connection(); Statement statement = connection.createStatement())
        {
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE parent_id=0 AND path='monitor'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE menu_id=3 AND parent_id=1 AND order_num=8"));
            assertEquals(3, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE parent_id=3"));
            assertEquals(4, count(statement, "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=7"));
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM sys_role_menu rm LEFT JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.menu_id IS NULL"));
        }
    }

    private int count(Statement statement, String sql) throws Exception
    {
        try (ResultSet result = statement.executeQuery(sql))
        {
            result.next();
            return result.getInt(1);
        }
    }

    private Connection connection() throws Exception
    {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
