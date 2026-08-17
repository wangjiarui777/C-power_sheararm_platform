package db.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class LowCodeV2MigrationTest
{
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ry_lowcode").withUsername("test").withPassword("test");

    @Test
    void createsMetadataStoreAndKeepsLegacyGeneratorUntouched() throws Exception
    {
        try (Connection connection = connection(); Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE gen_table(table_id BIGINT PRIMARY KEY, table_name VARCHAR(200))");
            statement.execute("INSERT INTO gen_table VALUES(7,'legacy_asset')");
            statement.execute("CREATE TABLE sys_menu(menu_id BIGINT AUTO_INCREMENT PRIMARY KEY,menu_name VARCHAR(50),parent_id BIGINT,order_num INT,path VARCHAR(200),component VARCHAR(255),query VARCHAR(255),route_name VARCHAR(50),is_frame INT,is_cache INT,menu_type CHAR(1),visible CHAR(1),status CHAR(1),perms VARCHAR(100),icon VARCHAR(100),create_by VARCHAR(64),create_time DATETIME,remark VARCHAR(500))");
            statement.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,UNIQUE KEY(role_id,menu_id))");
            statement.execute("INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type) VALUES('系统工具',0,1,'tool','M')");
        }
        Flyway flyway = Flyway.configure().locations(new String[0])
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .baselineOnMigrate(true).baselineVersion("2026081300")
            .javaMigrations(new V2026081301__LowCodeV2Platform(), new V2026081703__RemoveLowCodeProjectPermissions()).load();
        assertEquals(2, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (Connection connection = connection(); Statement statement = connection.createStatement())
        {
            assertTrue(exists(statement, "SELECT response_json FROM lc_action_log LIMIT 1"));
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_template")) { result.next(); assertEquals(2, result.getInt(1)); }
            try (ResultSet result = statement.executeQuery("SELECT metadata_json FROM lc_template"))
            {
                while (result.next()) assertFalse(result.getString(1).contains("permissions"));
            }
            try (ResultSet result = statement.executeQuery("SELECT table_name FROM gen_table WHERE table_id=7")) { result.next(); assertEquals("legacy_asset", result.getString(1)); }
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM sys_menu WHERE perms='tool:lowcode:publish'")) { result.next(); assertEquals(1, result.getInt(1)); }
        }
    }

    private Connection connection() throws Exception { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
    private boolean exists(Statement statement, String sql) { try { statement.executeQuery(sql).close(); return true; } catch (Exception ex) { return false; } }
}
