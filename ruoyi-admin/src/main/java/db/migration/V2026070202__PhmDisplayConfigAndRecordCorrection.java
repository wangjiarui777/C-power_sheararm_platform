package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026070202__PhmDisplayConfigAndRecordCorrection extends BaseJavaMigration
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
        createRecordCorrectionTable(connection);
        addIndex(connection, "phm_record_correction", "idx_record_correction_record",
            "ALTER TABLE phm_record_correction ADD INDEX idx_record_correction_record (record_type, record_id)");
        addIndex(connection, "phm_record_correction", "idx_record_correction_time",
            "ALTER TABLE phm_record_correction ADD INDEX idx_record_correction_time (create_time)");
    }

    private void createRecordCorrectionTable(Connection connection) throws SQLException
    {
        execute(connection, """
            CREATE TABLE IF NOT EXISTS phm_record_correction (
              id BIGINT NOT NULL PRIMARY KEY,
              record_type VARCHAR(64) NOT NULL COMMENT '事实记录类型',
              record_id VARCHAR(64) NOT NULL COMMENT '事实记录ID或业务键',
              correction_remark VARCHAR(1000) NULL COMMENT '订正/备注内容',
              tags VARCHAR(500) NULL COMMENT '标签，逗号分隔',
              operator_name VARCHAR(64) NULL COMMENT '操作人',
              create_time DATETIME NULL COMMENT '创建时间',
              update_time DATETIME NULL COMMENT '更新时间'
            ) COMMENT='PHM事实记录订正表'
            """);
    }

    private void addIndex(Connection connection, String tableName, String indexName, String sql) throws SQLException
    {
        try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, tableName, false, false))
        {
            while (rs.next())
            {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME")))
                {
                    return;
                }
            }
        }
        execute(connection, sql);
    }

    private void execute(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }
}
