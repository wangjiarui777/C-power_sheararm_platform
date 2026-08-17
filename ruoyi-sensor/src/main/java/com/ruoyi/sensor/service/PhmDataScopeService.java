package com.ruoyi.sensor.service;

import java.util.List;
import com.ruoyi.common.utils.SecurityUtils;
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

    public List<PhmDeviceEntity> listDevices(PhmDeviceScopeQuery query)
    {
        applyUserScope(query);
        return deviceMapper.selectScopedDeviceList(query);
    }

    public PhmDeviceEntity getDevice(PhmDeviceScopeQuery query)
    {
        applyUserScope(query);
        List<PhmDeviceEntity> rows = deviceMapper.selectScopedDeviceList(query);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void applyUserScope(PhmDeviceScopeQuery query)
    {
        if (query == null)
        {
            throw new IllegalArgumentException("设备查询不能为空");
        }
        if (SecurityUtils.isAdmin())
        {
            query.setScopeUserId(null);
            return;
        }
        Long userId = SecurityUtils.getUserId();
        query.setScopeUserId(userId == null ? -1L : userId);
    }
}
