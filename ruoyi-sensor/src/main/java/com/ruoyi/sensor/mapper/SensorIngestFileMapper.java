package com.ruoyi.sensor.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.sensor.domain.entity.SensorIngestFileEntity;
import com.ruoyi.sensor.domain.query.SensorIngestFileQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SensorIngestFileMapper extends BaseMapper<SensorIngestFileEntity>
{
    List<SensorIngestFileEntity> selectScopedList(SensorIngestFileQuery query);

    int associate(@Param("id") Long id, @Param("deviceId") Long deviceId,
        @Param("deviceCode") String deviceCode, @Param("pointId") Long pointId,
        @Param("pointCode") String pointCode, @Param("channelNo") Integer channelNo);

    int retry(@Param("id") Long id);
}
