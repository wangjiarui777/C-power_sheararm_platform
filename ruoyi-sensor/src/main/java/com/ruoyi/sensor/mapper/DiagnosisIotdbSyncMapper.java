package com.ruoyi.sensor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.sensor.domain.entity.DiagnosisIotdbSyncEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.Date;

@Mapper
public interface DiagnosisIotdbSyncMapper extends BaseMapper<DiagnosisIotdbSyncEntity>
{
    @Select("SELECT COALESCE(SUM(attempt_count), 0) FROM diagnosis_iotdb_sync")
    Long selectTotalRetryCount();

    @Select("SELECT MAX(synced_time) FROM diagnosis_iotdb_sync WHERE sync_status='SYNCED'")
    Date selectLastSyncedTime();
}
