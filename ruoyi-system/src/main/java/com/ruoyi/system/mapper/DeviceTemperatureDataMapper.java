package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.DeviceTemperatureData;

public interface DeviceTemperatureDataMapper
{
    DeviceTemperatureData selectDeviceTemperatureDataById(Long dataId);

    List<DeviceTemperatureData> selectDeviceTemperatureDataList(DeviceTemperatureData deviceTemperatureData);

    List<DeviceTemperatureData> selectRecentDeviceTemperatureDataList();

    int insertDeviceTemperatureData(DeviceTemperatureData deviceTemperatureData);

    int updateDeviceTemperatureData(DeviceTemperatureData deviceTemperatureData);

    int deleteDeviceTemperatureDataById(Long dataId);

    int deleteDeviceTemperatureDataByIds(Long[] dataIds);
}
