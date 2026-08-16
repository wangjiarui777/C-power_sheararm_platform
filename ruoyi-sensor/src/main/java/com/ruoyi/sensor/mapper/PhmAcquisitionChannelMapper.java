package com.ruoyi.sensor.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.sensor.domain.entity.PhmAcquisitionChannelEntity;
import com.ruoyi.sensor.domain.query.PhmAcquisitionChannelQuery;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PhmAcquisitionChannelMapper extends BaseMapper<PhmAcquisitionChannelEntity>
{
    List<PhmAcquisitionChannelEntity> selectScopedList(PhmAcquisitionChannelQuery query);
}
