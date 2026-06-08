package com.ruoyi.sensor.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.sensor.domain.DeviceTemperatureData;
import com.ruoyi.sensor.mapper.DeviceTemperatureDataMapper;
import com.ruoyi.sensor.service.IDeviceTemperatureDataService;

@Service
public class DeviceTemperatureDataServiceImpl implements IDeviceTemperatureDataService
{
    @Autowired
    private DeviceTemperatureDataMapper deviceTemperatureDataMapper;

    @Override
    public DeviceTemperatureData selectDeviceTemperatureDataById(Long dataId)
    {
        return deviceTemperatureDataMapper.selectDeviceTemperatureDataById(dataId);
    }

    @Override
    public List<DeviceTemperatureData> selectDeviceTemperatureDataList(DeviceTemperatureData deviceTemperatureData)
    {
        return deviceTemperatureDataMapper.selectDeviceTemperatureDataList(deviceTemperatureData);
    }

    @Override
    public List<DeviceTemperatureData> selectRecentDeviceTemperatureDataList()
    {
        return deviceTemperatureDataMapper.selectRecentDeviceTemperatureDataList();
    }

    @Override
    public int insertDeviceTemperatureData(DeviceTemperatureData deviceTemperatureData)
    {
        return deviceTemperatureDataMapper.insertDeviceTemperatureData(deviceTemperatureData);
    }

    @Override
    public int updateDeviceTemperatureData(DeviceTemperatureData deviceTemperatureData)
    {
        return deviceTemperatureDataMapper.updateDeviceTemperatureData(deviceTemperatureData);
    }

    @Override
    public int deleteDeviceTemperatureDataByIds(Long[] dataIds)
    {
        return deviceTemperatureDataMapper.deleteDeviceTemperatureDataByIds(dataIds);
    }
}

