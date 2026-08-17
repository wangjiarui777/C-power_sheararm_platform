package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysUserDevice;
import com.ruoyi.system.mapper.SysUserDeviceMapper;
import com.ruoyi.system.service.ISysUserDeviceService;

@Service
public class SysUserDeviceServiceImpl implements ISysUserDeviceService
{
    private final SysUserDeviceMapper mapper;

    public SysUserDeviceServiceImpl(SysUserDeviceMapper mapper)
    {
        this.mapper = mapper;
    }

    @Override
    public List<Long> selectDeviceIdsByUserId(Long userId)
    {
        return userId == null ? List.of() : mapper.selectDeviceIdsByUserId(userId);
    }

    @Override
    @Transactional
    public void replaceUserDevices(Long userId, Long[] deviceIds, String operator)
    {
        if (userId == null)
        {
            throw new ServiceException("用户不能为空");
        }
        mapper.deleteByUserId(userId);
        if (deviceIds == null || deviceIds.length == 0)
        {
            return;
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(Arrays.asList(deviceIds));
        uniqueIds.remove(null);
        if (uniqueIds.isEmpty())
        {
            return;
        }
        List<SysUserDevice> relations = new ArrayList<>(uniqueIds.size());
        for (Long deviceId : uniqueIds)
        {
            SysUserDevice relation = new SysUserDevice();
            relation.setUserId(userId);
            relation.setDeviceId(deviceId);
            relation.setCreateBy(operator);
            relation.setCreateTime(new Date());
            relations.add(relation);
        }
        mapper.batchInsert(relations);
    }

    @Override
    @Transactional
    public void deleteByUserIds(Long[] userIds)
    {
        if (userIds != null && userIds.length > 0)
        {
            mapper.deleteByUserIds(userIds);
        }
    }
}
