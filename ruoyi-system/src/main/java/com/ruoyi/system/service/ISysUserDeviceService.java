package com.ruoyi.system.service;

import java.util.List;

/** User-to-device authorization service. */
public interface ISysUserDeviceService
{
    List<Long> selectDeviceIdsByUserId(Long userId);

    void replaceUserDevices(Long userId, Long[] deviceIds, String operator);

    void deleteByUserIds(Long[] userIds);
}
