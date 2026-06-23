package com.ruoyi.sensor.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PhmDeviceMapper extends BaseMapper<PhmDeviceEntity>
{
    List<PhmDeviceEntity> selectScopedDeviceList(PhmDeviceScopeQuery query);
}
