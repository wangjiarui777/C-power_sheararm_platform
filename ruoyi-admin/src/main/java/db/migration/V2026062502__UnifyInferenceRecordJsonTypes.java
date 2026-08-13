package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * 统一 enhanced_inference_record 的 JSON 列类型。
 *
 * <p>原 wave_json 和 spectrum_json 使用 LONGTEXT，而 top_probabilities 和 evidence
 * 使用原生 JSON。统一为 JSON 类型以启用 MySQL 内置 JSON 校验。</p>
 */
public class V2026062502__UnifyInferenceRecordJsonTypes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement stmt = context.getConnection().createStatement()) {
            stmt.execute("ALTER TABLE enhanced_inference_record "
                + "MODIFY COLUMN wave_json JSON COMMENT '波形数据数组', "
                + "MODIFY COLUMN spectrum_json JSON COMMENT '频谱数据数组'");
        }
    }
}
