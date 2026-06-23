package com.ruoyi.sensor.service;

import java.util.List;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.mapper.PhmDeviceMapper;
import org.springframework.stereotype.Service;

@Service
public class PhmDataScopeService
{
    private final PhmDeviceMapper deviceMapper;

    public PhmDataScopeService(PhmDeviceMapper deviceMapper)
    {
        this.deviceMapper = deviceMapper;
    }

    @DataScope(deptAlias = "d")
    public List<PhmDeviceEntity> listDevices(PhmDeviceScopeQuery query)
    {
        return deviceMapper.selectScopedDeviceList(query);
    }

    @DataScope(deptAlias = "d")
    public PhmDeviceEntity getDevice(PhmDeviceScopeQuery query)
    {
        List<PhmDeviceEntity> rows = deviceMapper.selectScopedDeviceList(query);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
