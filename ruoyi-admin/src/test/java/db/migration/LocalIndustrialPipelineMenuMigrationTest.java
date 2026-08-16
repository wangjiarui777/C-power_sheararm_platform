package db.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in verification for workstations that provide MySQL but not Docker. */
@EnabledIfSystemProperty(named = "local.mysql.verify", matches = "true")
class LocalIndustrialPipelineMenuMigrationTest
{
    private static final String DATABASE = "ry_permission_migration_test";
    private static final String SERVER_URL = "jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Hong_Kong";
    private static final String USER = System.getProperty("local.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("local.mysql.password", "");
    private static final String DATABASE_URL = "jdbc:mysql://localhost:3306/" + DATABASE
        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Hong_Kong";

    @BeforeAll
    static void createSchema() throws Exception
    {
        assertEquals("ry_permission_migration_test", DATABASE);
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = connection.createStatement())
        {
            statement.execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
            statement.execute("CREATE DATABASE `" + DATABASE + "` CHARACTER SET utf8mb4");
        }
    }

    @AfterAll
    static void dropSchema() throws Exception
    {
        assertEquals("ry_permission_migration_test", DATABASE);
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = connection.createStatement())
        {
            statement.execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
        }
    }

    @Test
    void verifiesMenuMigrationAgainstLocalMySql() throws Exception
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
            statement.execute("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type) VALUES(10,'系统工具',0,1,'tool','M')");
            statement.execute("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,route_name,menu_type,perms) VALUES(11,'低代码',10,1,'lowcode','tool/lowcode/index','LowCode','C','tool:lowcode:list')");
            statement.execute("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type) VALUES(20,'数据接入旧项',0,8,'sensor-access','M'),(21,'数据接入重复项',0,9,'sensor-access','M')");
            statement.execute("INSERT INTO sys_role_menu VALUES(2,11),(2,21)");
        }

        Flyway flyway = Flyway.configure().dataSource(DATABASE_URL, USER, PASSWORD)
            .baselineOnMigrate(true).baselineVersion("2026081501")
            .javaMigrations(new V2026081502__IndustrialPipelineMenus()).load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);

        try (Connection connection = connection(); Statement statement = connection.createStatement())
        {
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE parent_id=0 AND path='sensor-access' AND menu_type='M'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE component='sensor/access/points' AND perms='sensor:channel:list'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE component='sensor/ingest/files' AND perms='sensor:ingest:list'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE perms='tool:lowcode:test'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE perms='tool:lowcode:activate'"));
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM sys_menu c LEFT JOIN sys_menu p ON p.menu_id=c.parent_id WHERE c.parent_id<>0 AND p.menu_id IS NULL"));
            assertEquals(2, count(statement, "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=2"));
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM sys_menu WHERE path='sensor-analysis'"));
        }
    }

    private static Connection connection() throws Exception
    {
        return DriverManager.getConnection(DATABASE_URL, USER, PASSWORD);
    }

    private static int count(Statement statement, String sql) throws Exception
    {
        try (ResultSet result = statement.executeQuery(sql)) { result.next(); return result.getInt(1); }
    }
}
