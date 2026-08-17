package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.SysUserDevice;
import org.apache.ibatis.annotations.Param;

/** User-to-device authorization mapper. */
public interface SysUserDeviceMapper
{
    List<Long> selectDeviceIdsByUserId(@Param("userId") Long userId);

    int deleteByUserId(@Param("userId") Long userId);

    int deleteByUserIds(@Param("userIds") Long[] userIds);

    int batchInsert(@Param("list") List<SysUserDevice> relations);
}
