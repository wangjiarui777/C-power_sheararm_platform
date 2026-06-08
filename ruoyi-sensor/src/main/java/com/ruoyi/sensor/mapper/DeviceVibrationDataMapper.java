package com.ruoyi.sensor.mapper;

import java.util.List;
import com.ruoyi.sensor.domain.DeviceVibrationData;

public interface DeviceVibrationDataMapper
{
    DeviceVibrationData selectDeviceVibrationDataById(Long dataId);

    List<DeviceVibrationData> selectDeviceVibrationDataList(DeviceVibrationData deviceVibrationData);

    List<DeviceVibrationData> selectRecentDeviceVibrationDataList();

    int insertDeviceVibrationData(DeviceVibrationData deviceVibrationData);

    int batchInsertDeviceVibrationData(List<DeviceVibrationData> deviceVibrationDataList);

    int updateDeviceVibrationData(DeviceVibrationData deviceVibrationData);

    int deleteDeviceVibrationDataById(Long dataId);

    int deleteDeviceVibrationDataByIds(Long[] dataIds);
}

