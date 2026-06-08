package com.ruoyi.sensor.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.sensor.domain.DeviceTemperatureData;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.domain.vo.MonitoringOverviewVo;
import com.ruoyi.sensor.domain.vo.MonitoringOverviewVo.DevicePointVo;
import com.ruoyi.sensor.service.IDeviceTemperatureDataService;
import com.ruoyi.sensor.service.IDeviceVibrationDataService;
import com.ruoyi.sensor.service.IMonitoringService;

@Service
public class MonitoringServiceImpl implements IMonitoringService
{
    private static final double VIBRATION_THRESHOLD = 0.20D;
    private static final double TEMPERATURE_THRESHOLD = 37.5D;

    @Autowired
    private IDeviceVibrationDataService vibrationDataService;

    @Autowired
    private IDeviceTemperatureDataService temperatureDataService;

    @Override
    public MonitoringOverviewVo getOverview()
    {
        List<DeviceVibrationData> vibrationList = vibrationDataService.selectRecentDeviceVibrationDataList();
        List<DeviceTemperatureData> temperatureList = temperatureDataService.selectRecentDeviceTemperatureDataList();

        List<DeviceVibrationData> sortedVibration = vibrationList.stream()
                .sorted(Comparator.comparing(DeviceVibrationData::getSampleTime).reversed())
                .collect(Collectors.toList());
        List<DeviceTemperatureData> sortedTemperature = temperatureList.stream()
                .sorted(Comparator.comparing(DeviceTemperatureData::getCollectionTime).reversed())
                .collect(Collectors.toList());

        MonitoringOverviewVo vo = new MonitoringOverviewVo();
        Set<String> deviceCodes = vibrationList.stream().map(DeviceVibrationData::getDeviceCode)
                .collect(Collectors.toSet());
        deviceCodes.addAll(temperatureList.stream().map(DeviceTemperatureData::getDeviceCode).collect(Collectors.toSet()));
        vo.setDeviceCount(deviceCodes.size());
        vo.setLatestVibration(sortedVibration.isEmpty() ? null : sortedVibration.get(0).getVibrationValue().doubleValue());
        vo.setLatestTemperature(sortedTemperature.isEmpty() ? null : sortedTemperature.get(0).getTemperatureValue().doubleValue());
        vo.setUpdateTime(sortedVibration.isEmpty() ? (sortedTemperature.isEmpty() ? null : String.valueOf(sortedTemperature.get(0).getCollectionTime())) : String.valueOf(sortedVibration.get(0).getSampleTime()));
        vo.setVibrationThreshold(VIBRATION_THRESHOLD);
        vo.setTemperatureThreshold(TEMPERATURE_THRESHOLD);

        MonitoringOverviewVo.TrendVo vibrationTrend = new MonitoringOverviewVo.TrendVo();
        vibrationTrend.setxData(sortedVibration.stream()
                .map(item -> String.valueOf(item.getSampleTime()))
                .collect(Collectors.toList()));
        vibrationTrend.setyData(sortedVibration.stream()
                .map(item -> item.getVibrationValue().doubleValue())
                .collect(Collectors.toList()));
        vo.setVibrationTrend(vibrationTrend);

        MonitoringOverviewVo.TrendVo temperatureTrend = new MonitoringOverviewVo.TrendVo();
        temperatureTrend.setxData(sortedTemperature.stream()
                .map(item -> String.valueOf(item.getCollectionTime()))
                .collect(Collectors.toList()));
        temperatureTrend.setyData(sortedTemperature.stream()
                .map(item -> item.getTemperatureValue().doubleValue())
                .collect(Collectors.toList()));
        vo.setTemperatureTrend(temperatureTrend);

        List<DevicePointVo> points = new ArrayList<>();
        for (String code : deviceCodes)
        {
            DevicePointVo point = new DevicePointVo();
            point.setDeviceCode(code);
            vibrationList.stream().filter(item -> code.equals(item.getDeviceCode())).findFirst().ifPresent(item -> point.setVibrationValue(item.getVibrationValue().doubleValue()));
            temperatureList.stream().filter(item -> code.equals(item.getDeviceCode())).findFirst().ifPresent(item -> point.setTemperatureValue(item.getTemperatureValue().doubleValue()));
            points.add(point);
        }
        vo.setDevicePoints(points);
        return vo;
    }
}

