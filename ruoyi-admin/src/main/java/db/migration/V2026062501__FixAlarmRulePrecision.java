package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * 修正 phm_alarm_rule 告警阈值字段精度。
 *
 * <p>原 DECIMAL(12,4) 精度过剩（每个值 6 字节），典型告警阈值如 75.5 仅需 DECIMAL(9,4)。
 * 此迁移不影响已有数据。</p>
 */
@SuppressWarnings("squid:S1192")
public class V2026062501__FixAlarmRulePrecision extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement stmt = context.getConnection().createStatement()) {
            stmt.execute("ALTER TABLE phm_alarm_rule "
                + "MODIFY COLUMN high_limit DECIMAL(9,4) NULL, "
                + "MODIFY COLUMN high_high_limit DECIMAL(9,4) NULL, "
                + "MODIFY COLUMN growth_high_limit DECIMAL(9,4) NULL, "
                + "MODIFY COLUMN growth_high_high_limit DECIMAL(9,4) NULL");
        }
    }
}
