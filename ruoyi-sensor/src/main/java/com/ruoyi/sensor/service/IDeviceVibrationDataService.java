package com.ruoyi.sensor.service;

import java.util.List;
import com.ruoyi.sensor.domain.DeviceVibrationData;

/**
 * Device vibration data service interface.
 */
public interface IDeviceVibrationDataService
{
    DeviceVibrationData selectDeviceVibrationDataById(Long dataId);

    List<DeviceVibrationData> selectDeviceVibrationDataList(DeviceVibrationData deviceVibrationData);

    List<DeviceVibrationData> selectRecentDeviceVibrationDataList();

    int insertDeviceVibrationData(DeviceVibrationData deviceVibrationData);

    int batchInsertDeviceVibrationData(List<DeviceVibrationData> deviceVibrationDataList);

    int updateDeviceVibrationData(DeviceVibrationData deviceVibrationData);

    int deleteDeviceVibrationDataByIds(Long[] dataIds);
}

