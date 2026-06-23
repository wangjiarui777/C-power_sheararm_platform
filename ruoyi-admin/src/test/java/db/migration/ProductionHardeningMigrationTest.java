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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class ProductionHardeningMigrationTest
{
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ry_vue")
        .withUsername("test")
        .withPassword("test");

    @Test
    void existingDatabaseMigratesTwiceWithoutDuplicatesOrDataLoss() throws Exception
    {
        createExistingBaseline();
        Flyway flyway = Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .baselineOnMigrate(true)
            .baselineVersion("2026041700")
            .javaMigrations(new V2026062301__ProductionHardening())
            .load();

        assertEquals(1, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);

        try (Connection connection = connection(); Statement statement = connection.createStatement())
        {
            assertTrue(exists(statement, "SELECT 1 FROM sensor_inference_task LIMIT 1"));
            assertTrue(exists(statement, "SELECT dept_id FROM phm_device WHERE id=1"));
            assertTrue(exists(statement, "SELECT event_id FROM device_vibration_data LIMIT 1"));
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM phm_device WHERE id=1"))
            {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    private void createExistingBaseline() throws Exception
    {
        try (Connection connection = connection(); Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE phm_device(id BIGINT PRIMARY KEY, device_code VARCHAR(64))");
            statement.execute("INSERT INTO phm_device(id,device_code) VALUES(1,'DEV-001')");
            statement.execute("CREATE TABLE phm_attachment(id BIGINT PRIMARY KEY, file_url VARCHAR(255), file_ext VARCHAR(32))");
            statement.execute("CREATE TABLE device_vibration_data(data_id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE device_temperature_data(data_id BIGINT PRIMARY KEY)");
        }
    }

    private Connection connection() throws Exception
    {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private boolean exists(Statement statement, String sql)
    {
        try
        {
            statement.executeQuery(sql).close();
            return true;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }
}
