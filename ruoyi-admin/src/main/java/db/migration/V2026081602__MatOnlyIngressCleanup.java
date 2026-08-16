package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Removes legacy collector/frame ingress and leaves the MAT TCP intake model. */
public class V2026081602__MatOnlyIngressCleanup extends BaseJavaMigration
{
    @Override
    public boolean canExecuteInTransaction()
    {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        validateChannelMappings(connection);
        validateDiagnosisBindings(connection);
        addColumn(connection, "sensor_ingest_file", "acquisition_time",
            "ALTER TABLE sensor_ingest_file ADD COLUMN acquisition_time DATETIME NULL AFTER retry_count");
        cleanupMenus(connection);
        dropTable(connection, "sensor_collector_credential");
        dropTable(connection, "phm_realtime_diagnosis_policy");
        simplifyDiagnosisBindings(connection);
        simplifyAcquisitionChannels(connection);
        dropColumn(connection, "sensor_inference_task", "collector_id");
        dropColumn(connection, "sensor_inference_task", "window_id");
        dropColumn(connection, "sensor_inference_task", "deadline_at");
        dropColumn(connection, "enhanced_inference_record", "collector_id");
        dropColumn(connection, "enhanced_inference_record", "window_id");
    }

    private void validateChannelMappings(Connection connection) throws SQLException
    {
        if (!tableExists(connection, "phm_acquisition_channel")) return;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT device_id, channel_no, COUNT(*) AS total FROM phm_acquisition_channel "
                + "GROUP BY device_id, channel_no HAVING COUNT(*) > 1"))
        {
            try (ResultSet rows = statement.executeQuery())
            {
                if (rows.next())
                {
                    throw new FlywayException("MAT-only 迁移发现重复设备/物理通道映射: device_id="
                        + rows.getLong("device_id") + ", channel_no=" + rows.getInt("channel_no")
                        + ", count=" + rows.getInt("total"));
                }
            }
        }
    }

    private void validateDiagnosisBindings(Connection connection) throws SQLException
    {
        if (!tableExists(connection, "phm_diagnosis_binding")) return;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT point_id, COUNT(*) AS total FROM phm_diagnosis_binding "
                + "WHERE enabled=1 GROUP BY point_id HAVING COUNT(*) > 1"))
        {
            try (ResultSet rows = statement.executeQuery())
            {
                if (rows.next())
                {
                    throw new FlywayException("MAT-only 迁移发现测点存在多个启用诊断模型: point_id="
                        + rows.getLong("point_id") + ", count=" + rows.getInt("total"));
                }
            }
        }
    }

    private void simplifyAcquisitionChannels(Connection connection) throws SQLException
    {
        if (!tableExists(connection, "phm_acquisition_channel")) return;
        dropIndex(connection, "phm_acquisition_channel", "uk_phm_channel_collector_module_no");
        dropColumn(connection, "phm_acquisition_channel", "collector_id");
        dropColumn(connection, "phm_acquisition_channel", "module_no");
        if (!indexExists(connection, "phm_acquisition_channel", "uk_phm_channel_device_no"))
        {
            execute(connection, "ALTER TABLE phm_acquisition_channel ADD UNIQUE KEY "
                + "uk_phm_channel_device_no (device_id, channel_no)");
        }
    }

    private void simplifyDiagnosisBindings(Connection connection) throws SQLException
    {
        if (!tableExists(connection, "phm_diagnosis_binding")) return;
        // Historical migrations stored the mapping row id in this column. MAT V2
        // uses the physical channel number, so convert before removing old fields.
        if (tableExists(connection, "phm_acquisition_channel"))
        {
            execute(connection, "UPDATE phm_diagnosis_binding b "
                + "JOIN phm_acquisition_channel c ON c.device_id=b.device_id AND c.point_id=b.point_id "
                + "SET b.channel_id=c.channel_no WHERE b.channel_id IS NULL OR b.channel_id<>c.channel_no");
        }
        dropIndex(connection, "phm_diagnosis_binding", "uk_phm_binding_point_model_input");
        dropColumn(connection, "phm_diagnosis_binding", "input_mode");
        dropColumn(connection, "phm_diagnosis_binding", "window_size");
        dropColumn(connection, "phm_diagnosis_binding", "stride");
        dropColumn(connection, "phm_diagnosis_binding", "trigger_policy");
        dropColumn(connection, "phm_diagnosis_binding", "min_confidence");
        if (tableExists(connection, "lc_template"))
        {
            String metadata = "{\"schemaVersion\":2,\"preset\":\"sensor-diagnosis\","
                + "\"dataSource\":\"mysql\",\"model\":{\"table\":\"phm_diagnosis_binding\","
                + "\"primaryKey\":\"id\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"long\",\"readOnly\":true,\"generated\":\"long\"},"
                + "{\"name\":\"device_id\",\"type\":\"entity\",\"required\":true},"
                + "{\"name\":\"device_code\",\"type\":\"text\",\"required\":true,\"query\":true},"
                + "{\"name\":\"point_id\",\"type\":\"entity\",\"required\":true},"
                + "{\"name\":\"channel_id\",\"type\":\"number\",\"required\":true},"
                + "{\"name\":\"model_type\",\"type\":\"dict\",\"required\":true,\"query\":true},"
                + "{\"name\":\"model_version\",\"type\":\"remote\"},"
                + "{\"name\":\"enabled\",\"type\":\"switch\",\"defaultValue\":true},"
                + "{\"name\":\"create_time\",\"type\":\"datetime\",\"readOnly\":true,\"list\":false},"
                + "{\"name\":\"update_time\",\"type\":\"datetime\",\"readOnly\":true,\"list\":false},"
                + "{\"name\":\"remark\",\"type\":\"textarea\"}],\"relations\":[]},"
                + "\"pages\":[{\"code\":\"binding\",\"type\":\"crud\",\"regions\":[\"query\",\"list\",\"form\",\"detail\"]}],"
                + "\"rules\":[],\"actions\":[],\"permissions\":{\"dataScope\":\"NONE\"}}";
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE lc_template SET metadata_json=? WHERE template_code='sensor-diagnosis'"))
            {
                statement.setString(1, metadata);
                statement.executeUpdate();
            }
        }
    }

    private void cleanupMenus(Connection connection) throws SQLException
    {
        if (!tableExists(connection, "sys_menu")) return;
        execute(connection, "CREATE TEMPORARY TABLE IF NOT EXISTS tmp_mat_cleanup_menu "
            + "(menu_id BIGINT PRIMARY KEY)");
        execute(connection, "INSERT IGNORE INTO tmp_mat_cleanup_menu(menu_id) "
            + "SELECT menu_id FROM sys_menu WHERE path IN ('realtime-diagnosis') "
            + "OR component LIKE '%RealtimeDiagnosis%' OR perms LIKE 'sensor:diagnosis:realtime:%' "
            + "OR perms LIKE 'sensor:collector:%' OR path LIKE '%collector%'");
        boolean added;
        do
        {
            try (Statement statement = connection.createStatement())
            {
                added = statement.executeUpdate("INSERT IGNORE INTO tmp_mat_cleanup_menu(menu_id) "
                    + "SELECT menu_id FROM sys_menu WHERE parent_id IN "
                    + "(SELECT menu_id FROM tmp_mat_cleanup_menu)") > 0;
            }
        }
        while (added);
        if (tableExists(connection, "sys_role_menu"))
        {
            execute(connection, "DELETE FROM sys_role_menu WHERE menu_id IN "
                + "(SELECT menu_id FROM tmp_mat_cleanup_menu)");
        }
        execute(connection, "DELETE FROM sys_menu WHERE menu_id IN "
            + "(SELECT menu_id FROM tmp_mat_cleanup_menu)");
        execute(connection, "DROP TEMPORARY TABLE IF EXISTS tmp_mat_cleanup_menu");
    }

    private void dropTable(Connection connection, String table) throws SQLException
    {
        if (tableExists(connection, table)) execute(connection, "DROP TABLE " + table);
    }

    private void dropColumn(Connection connection, String table, String column) throws SQLException
    {
        if (columnExists(connection, table, column))
        {
            execute(connection, "ALTER TABLE " + table + " DROP COLUMN " + column);
        }
    }

    private void addColumn(Connection connection, String table, String column, String sql) throws SQLException
    {
        if (tableExists(connection, table) && !columnExists(connection, table, column)) execute(connection, sql);
    }

    private void dropIndex(Connection connection, String table, String index) throws SQLException
    {
        if (indexExists(connection, table, index)) execute(connection, "ALTER TABLE " + table + " DROP INDEX " + index);
    }

    private boolean tableExists(Connection connection, String table) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, table,
            new String[] {"TABLE"}))
        {
            return result.next();
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column))
        {
            return result.next();
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException
    {
        try (ResultSet result = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false))
        {
            while (result.next())
            {
                if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) return true;
            }
            return false;
        }
    }

    private void execute(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
