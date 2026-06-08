package com.ruoyi.sensor.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.mapper.DeviceVibrationDataMapper;
import com.ruoyi.sensor.service.IDeviceVibrationDataService;

@Service
public class DeviceVibrationDataServiceImpl implements IDeviceVibrationDataService
{
    @Autowired
    private DeviceVibrationDataMapper deviceVibrationDataMapper;

    @Override
    public DeviceVibrationData selectDeviceVibrationDataById(Long dataId)
    {
        return deviceVibrationDataMapper.selectDeviceVibrationDataById(dataId);
    }

    @Override
    public List<DeviceVibrationData> selectDeviceVibrationDataList(DeviceVibrationData deviceVibrationData)
    {
        return deviceVibrationDataMapper.selectDeviceVibrationDataList(deviceVibrationData);
    }

    @Override
    public List<DeviceVibrationData> selectRecentDeviceVibrationDataList()
    {
        return deviceVibrationDataMapper.selectRecentDeviceVibrationDataList();
    }

    @Override
    public int insertDeviceVibrationData(DeviceVibrationData deviceVibrationData)
    {
        return deviceVibrationDataMapper.insertDeviceVibrationData(deviceVibrationData);
    }

    @Override
    public int batchInsertDeviceVibrationData(List<DeviceVibrationData> deviceVibrationDataList)
    {
        if (deviceVibrationDataList == null || deviceVibrationDataList.isEmpty())
        {
            return 0;
        }
        return deviceVibrationDataMapper.batchInsertDeviceVibrationData(deviceVibrationDataList);
    }

    @Override
    public int updateDeviceVibrationData(DeviceVibrationData deviceVibrationData)
    {
        return deviceVibrationDataMapper.updateDeviceVibrationData(deviceVibrationData);
    }

    @Override
    public int deleteDeviceVibrationDataByIds(Long[] dataIds)
    {
        return deviceVibrationDataMapper.deleteDeviceVibrationDataByIds(dataIds);
    }
}

