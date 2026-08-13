package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 为 phm_alarm_event 添加 (device_code, point_id, alarm_time) 复合索引。
 *
 * <p>覆盖常见查询 "某设备某测点在时间段内的告警"，
 * 替代仅靠单列 device_code+alarm_time 索引的性能瓶颈。</p>
 */
public class V2026062503__AddAlarmDevicePointIndex extends BaseJavaMigration {

    private static final String INDEX_NAME = "idx_device_point_time";

    @Override
    public void migrate(Context context) throws Exception {
        DatabaseMetaData meta = context.getConnection().getMetaData();
        try (ResultSet rs = meta.getIndexInfo(null, null, "phm_alarm_event", false, false)) {
            while (rs.next()) {
                if (INDEX_NAME.equals(rs.getString("INDEX_NAME"))) {
                    return; // 索引已存在，幂等跳过
                }
            }
        }
        try (Statement stmt = context.getConnection().createStatement()) {
            stmt.execute("CREATE INDEX " + INDEX_NAME
                + " ON phm_alarm_event(device_code, point_id, alarm_time)");
        }
    }
}
