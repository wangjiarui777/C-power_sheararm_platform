package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Repairs legacy PHM ownership and enforces a non-null department owner. */
public class V2026072201__PhmDeviceVisibility extends BaseJavaMigration
{
    private static final long DEFAULT_DEPARTMENT_ID = 105L;

    @Override
    public boolean canExecuteInTransaction()
    {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        requireTable(connection, "sys_dept");
        requireTable(connection, "phm_device");
        requireTable(connection, "phm_measure_point");
        requireEnabledDefaultDepartment(connection);

        executeUpdate(connection,
            "UPDATE phm_device SET dept_id = " + DEFAULT_DEPARTMENT_ID + " WHERE dept_id IS NULL");
        repairMeasurePointDeviceCodes(connection);

        if (count(connection, "SELECT COUNT(*) FROM phm_device WHERE dept_id IS NULL") > 0)
        {
            throw new FlywayException("PHM device department backfill is incomplete");
        }
        enforceDepartmentNotNull(connection);
    }

    private void requireEnabledDefaultDepartment(Connection connection) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT status FROM sys_dept WHERE dept_id = ?"))
        {
            statement.setLong(1, DEFAULT_DEPARTMENT_ID);
            try (ResultSet result = statement.executeQuery())
            {
                if (!result.next())
                {
                    throw new FlywayException("Required PHM default department 105 does not exist");
                }
                if (!"0".equals(result.getString(1)))
                {
                    throw new FlywayException("Required PHM default department 105 is disabled");
                }
            }
        }
    }

    private void repairMeasurePointDeviceCodes(Connection connection) throws SQLException
    {
        List<PointOwner> repairs = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT p.id, d.device_code FROM phm_measure_point p "
                     + "JOIN phm_device d ON d.id = p.device_id "
                     + "WHERE p.device_code IS NULL OR p.device_code <> d.device_code"))
        {
            while (result.next())
            {
                repairs.add(new PointOwner(result.getLong(1), result.getString(2)));
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE phm_measure_point SET device_code = ? WHERE id = ?"))
        {
            for (PointOwner repair : repairs)
            {
                update.setString(1, repair.deviceCode());
                update.setLong(2, repair.pointId());
                update.addBatch();
            }
            if (!repairs.isEmpty())
            {
                update.executeBatch();
            }
        }
    }

    private void enforceDepartmentNotNull(Connection connection) throws SQLException
    {
        String product = connection.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase().contains("h2"))
        {
            executeUpdate(connection, "ALTER TABLE phm_device ALTER COLUMN dept_id SET NOT NULL");
        }
        else
        {
            executeUpdate(connection,
                "ALTER TABLE phm_device MODIFY COLUMN dept_id BIGINT NOT NULL COMMENT 'Owning RuoYi department'");
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

    private long count(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql))
        {
            result.next();
            return result.getLong(1);
        }
    }

    private void executeUpdate(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private record PointOwner(long pointId, String deviceCode) {}
}
