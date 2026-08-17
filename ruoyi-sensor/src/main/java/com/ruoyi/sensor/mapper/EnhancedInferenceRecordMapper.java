package com.ruoyi.sensor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Date;
import java.util.Collection;
import java.util.List;

@Mapper
public interface EnhancedInferenceRecordMapper extends BaseMapper<EnhancedInferenceRecordEntity>
{
    @Select({"<script>",
        "SELECT r.* FROM enhanced_inference_record r INNER JOIN diagnosis_iotdb_sync s ON s.record_id=r.id",
        "WHERE 1=1",
        "<if test='deviceCode != null and deviceCode != \"\"'> AND r.device_code=#{deviceCode}</if>",
        "<if test='from != null'> AND r.create_time &gt;= #{from}</if>",
        "<if test='to != null'> AND r.create_time &lt;= #{to}</if>",
        "<if test='unsyncedOnly'> AND s.sync_status != 'SYNCED'</if>",
        "ORDER BY r.create_time DESC LIMIT #{limit}",
        "</script>"})
    List<EnhancedInferenceRecordEntity> selectManagedHistory(@Param("deviceCode") String deviceCode,
        @Param("from") Date from, @Param("to") Date to, @Param("unsyncedOnly") boolean unsyncedOnly,
        @Param("limit") int limit);

    @Select({"<script>",
        "SELECT r.* FROM enhanced_inference_record r INNER JOIN diagnosis_iotdb_sync s ON s.record_id=r.id",
        "WHERE 1=1",
        "<if test='deviceCode != null and deviceCode != \"\"'> AND r.device_code=#{deviceCode}</if>",
        "<if test='pointId != null'> AND r.point_id=#{pointId}</if>",
        "<if test='from != null'> AND r.create_time &gt;= #{from}</if>",
        "<if test='to != null'> AND r.create_time &lt;= #{to}</if>",
        "<if test='unsyncedOnly'> AND s.sync_status != 'SYNCED'</if>",
        "ORDER BY r.create_time DESC LIMIT #{limit}",
        "</script>"})
    List<EnhancedInferenceRecordEntity> selectManagedHistoryByPoint(@Param("deviceCode") String deviceCode,
        @Param("pointId") Long pointId, @Param("from") Date from, @Param("to") Date to,
        @Param("unsyncedOnly") boolean unsyncedOnly, @Param("limit") int limit);

    @Select({"<script>",
        "SELECT r.* FROM enhanced_inference_record r",
        "WHERE r.point_id IN",
        "<foreach collection='pointIds' item='pointId' open='(' separator=',' close=')'>#{pointId}</foreach>",
        "AND NOT EXISTS (",
        " SELECT 1 FROM enhanced_inference_record newer",
        " WHERE newer.point_id = r.point_id",
        " AND (",
        "  COALESCE(newer.sample_time, newer.create_time, '1970-01-01 00:00:00')",
        "    &gt; COALESCE(r.sample_time, r.create_time, '1970-01-01 00:00:00')",
        "  OR (COALESCE(newer.sample_time, newer.create_time, '1970-01-01 00:00:00')",
        "    = COALESCE(r.sample_time, r.create_time, '1970-01-01 00:00:00') AND newer.id &gt; r.id)",
        " )",
        ")",
        "ORDER BY r.point_id ASC",
        "</script>"})
    List<EnhancedInferenceRecordEntity> selectLatestByPointIds(@Param("pointIds") Collection<Long> pointIds);
}
