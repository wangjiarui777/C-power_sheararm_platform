package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.DeviceTemperatureData;

/**
 * Device temperature data service interface.
 */
public interface IDeviceTemperatureDataService
{
    DeviceTemperatureData selectDeviceTemperatureDataById(Long dataId);

    List<DeviceTemperatureData> selectDeviceTemperatureDataList(DeviceTemperatureData deviceTemperatureData);

    List<DeviceTemperatureData> selectRecentDeviceTemperatureDataList();

    int insertDeviceTemperatureData(DeviceTemperatureData deviceTemperatureData);

    int updateDeviceTemperatureData(DeviceTemperatureData deviceTemperatureData);

    int deleteDeviceTemperatureDataByIds(Long[] dataIds);
}
