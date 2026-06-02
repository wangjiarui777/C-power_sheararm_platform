package com.ruoyi.sensor.mapper;

import java.util.List;

import com.ruoyi.sensor.domain.entity.VibrationAnalysisBatchEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VibrationAnalysisBatchMapper
{
    VibrationAnalysisBatchEntity selectByBatchId(@Param("batchId") Long batchId);

    List<VibrationAnalysisBatchEntity> selectBatchList(@Param("batchId") Long batchId,
                                                       @Param("deviceCode") String deviceCode,
                                                       @Param("sampleRate") Double sampleRate);

    int insertBatch(VibrationAnalysisBatchEntity entity);

    int updateBatch(VibrationAnalysisBatchEntity entity);

    int deleteByBatchIds(@Param("ids") List<Long> ids);
}
