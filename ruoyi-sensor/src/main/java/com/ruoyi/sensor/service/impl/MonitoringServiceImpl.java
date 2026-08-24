package com.ruoyi.sensor.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.sensor.domain.DeviceTemperatureData;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.domain.vo.MonitoringOverviewVo;
import com.ruoyi.sensor.domain.vo.MonitoringOverviewVo.DevicePointVo;
import com.ruoyi.sensor.service.IDeviceTemperatureDataService;
import com.ruoyi.sensor.service.IDeviceVibrationDataService;
import com.ruoyi.sensor.service.IMonitoringService;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmEventEntity;
import com.ruoyi.sensor.mapper.PhmAlarmEventMapper;
import com.ruoyi.sensor.mapper.PhmDeviceMapper;

@Service
public class MonitoringServiceImpl implements IMonitoringService
{
    private static final double VIBRATION_THRESHOLD = 0.20D;
    private static final double TEMPERATURE_THRESHOLD = 37.5D;
    private static final String STATUS_UNHANDLED = "UNHANDLED";

    @Autowired
    private IDeviceVibrationDataService vibrationDataService;

    @Autowired
    private IDeviceTemperatureDataService temperatureDataService;

    @Autowired
    private PhmDeviceMapper deviceMapper;

    @Autowired
    private PhmAlarmEventMapper alarmEventMapper;

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
        populateHomepageOverview(vo);
        return vo;
    }

    /**
     * Adds the homepage's device-wide read model without changing the legacy
     * overview fields consumed by multi-channel monitoring clients.
     */
    @SuppressWarnings("unchecked")
    private void populateHomepageOverview(MonitoringOverviewVo vo)
    {
        List<PhmDeviceEntity> registeredDevices = deviceMapper.selectList(
                new LambdaQueryWrapper<PhmDeviceEntity>().orderByAsc(PhmDeviceEntity::getDeviceCode));
        List<String> deviceCodes = registeredDevices.stream()
                .map(PhmDeviceEntity::getDeviceCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toList());
        Map<String, DeviceVibrationData> latestVibration = latestVibrationByDevice(deviceCodes);
        Map<String, DeviceTemperatureData> latestTemperature = latestTemperatureByDevice(deviceCodes);
        Map<String, PhmAlarmEventEntity> activeAlarms = latestActiveAlarmsByDevice(deviceCodes);
        List<PhmAlarmEventEntity> unacknowledgedAlarms = deviceCodes.isEmpty() ? new ArrayList<>()
                : alarmEventMapper.selectList(new LambdaQueryWrapper<PhmAlarmEventEntity>()
                        .in(PhmAlarmEventEntity::getDeviceCode, deviceCodes)
                        .eq(PhmAlarmEventEntity::getStatus, STATUS_UNHANDLED)
                        .and(wrapper -> wrapper.isNull(PhmAlarmEventEntity::getWorkflowStatus)
                                .or().eq(PhmAlarmEventEntity::getWorkflowStatus, "NEW"))
                        .orderByDesc(PhmAlarmEventEntity::getAlarmTime));
        List<Map<String, Object>> devices = registeredDevices.stream()
                .map(device -> deviceCard(device, latestVibration.get(device.getDeviceCode()),
                        latestTemperature.get(device.getDeviceCode()), activeAlarms.get(device.getDeviceCode())))
                .collect(Collectors.toList());

        Date latestSampleTime = devices.stream()
                .map(item -> item.get("latestSampleTime"))
                .filter(Date.class::isInstance)
                .map(Date.class::cast)
                .max(Date::compareTo)
                .orElse(null);
        long onlineDevices = devices.stream()
                .filter(item -> "realtime".equals(item.get("telemetryFreshness")))
                .count();
        long abnormalDevices = devices.stream()
                .filter(item -> String.valueOf(item.get("status")).startsWith("level"))
                .count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDevices", devices.size());
        summary.put("onlineDevices", onlineDevices);
        summary.put("abnormalDevices", abnormalDevices);
        summary.put("unacknowledgedAlarms", unacknowledgedAlarms.size());
        summary.put("latestSampleTime", latestSampleTime);
        summary.put("dataDelaySeconds", latestSampleTime == null ? null
                : Math.max(0L, (System.currentTimeMillis() - latestSampleTime.getTime()) / 1000L));
        vo.setSummary(summary);
        vo.setDevices(devices);
        vo.setAlarms(unacknowledgedAlarms.stream().limit(10).collect(Collectors.toList()));
    }

    private Map<String, Object> deviceCard(PhmDeviceEntity device, DeviceVibrationData vibration,
                                           DeviceTemperatureData temperature, PhmAlarmEventEntity activeAlarm)
    {
        Date latestSampleTime = latestSampleTime(vibration, temperature);
        String status = activeAlarm == null
                ? "stopped".equals(device.getStatus()) ? "stopped" : "normal"
                : "level" + Math.min(5, Math.max(1, activeAlarm.getAlarmLevel() == null ? 1 : activeAlarm.getAlarmLevel()));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", device.getId());
        row.put("deviceCode", device.getDeviceCode());
        row.put("deviceName", device.getDeviceName());
        row.put("deviceType", device.getDeviceType());
        row.put("orgName", device.getOrgName());
        row.put("location", device.getLocation());
        row.put("status", status);
        row.put("statusText", statusText(status));
        row.put("healthIndex", device.getHealthIndex());
        row.put("faultType", activeAlarm == null ? null : activeAlarm.getDiagnosisResult());
        row.put("latestVibration", vibration == null ? null : vibration.getVibrationValue());
        row.put("latestTemperature", temperature == null ? null : temperature.getTemperatureValue());
        row.put("latestSampleTime", latestSampleTime);
        row.put("telemetryAvailable", vibration != null || temperature != null);
        row.put("telemetryFreshness", telemetryFreshness(latestSampleTime));
        return row;
    }

    private Map<String, DeviceVibrationData> latestVibrationByDevice(List<String> deviceCodes)
    {
        Map<String, DeviceVibrationData> result = new HashMap<>();
        for (String deviceCode : deviceCodes)
        {
            DeviceVibrationData query = new DeviceVibrationData();
            query.setDeviceCode(deviceCode);
            List<DeviceVibrationData> rows = vibrationDataService.selectDeviceVibrationDataList(query);
            if (rows != null && !rows.isEmpty()) result.put(deviceCode, rows.get(0));
        }
        return result;
    }

    private Map<String, DeviceTemperatureData> latestTemperatureByDevice(List<String> deviceCodes)
    {
        Map<String, DeviceTemperatureData> result = new HashMap<>();
        for (String deviceCode : deviceCodes)
        {
            DeviceTemperatureData query = new DeviceTemperatureData();
            query.setDeviceCode(deviceCode);
            List<DeviceTemperatureData> rows = temperatureDataService.selectDeviceTemperatureDataList(query);
            if (rows != null && !rows.isEmpty()) result.put(deviceCode, rows.get(0));
        }
        return result;
    }

    private Map<String, PhmAlarmEventEntity> latestActiveAlarmsByDevice(List<String> deviceCodes)
    {
        Map<String, PhmAlarmEventEntity> result = new HashMap<>();
        if (deviceCodes.isEmpty()) return result;
        for (PhmAlarmEventEntity alarm : alarmEventMapper.selectList(new LambdaQueryWrapper<PhmAlarmEventEntity>()
                .in(PhmAlarmEventEntity::getDeviceCode, deviceCodes)
                .eq(PhmAlarmEventEntity::getStatus, STATUS_UNHANDLED)
                .orderByDesc(PhmAlarmEventEntity::getAlarmLevel)
                .orderByDesc(PhmAlarmEventEntity::getAlarmTime)))
        {
            result.putIfAbsent(alarm.getDeviceCode(), alarm);
        }
        return result;
    }

    private Date latestSampleTime(DeviceVibrationData vibration, DeviceTemperatureData temperature)
    {
        Date vibrationTime = vibration == null ? null : vibration.getSampleTime();
        Date temperatureTime = temperature == null ? null : temperature.getCollectionTime();
        if (vibrationTime == null) return temperatureTime;
        if (temperatureTime == null) return vibrationTime;
        return vibrationTime.after(temperatureTime) ? vibrationTime : temperatureTime;
    }

    private String telemetryFreshness(Date sampleTime)
    {
        if (sampleTime == null) return "offline";
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - sampleTime.getTime()) / 1000L);
        if (ageSeconds <= 30) return "realtime";
        return ageSeconds <= 300 ? "delayed" : "offline";
    }

    private String statusText(String status)
    {
        if ("normal".equals(status)) return "正常";
        if ("stopped".equals(status)) return "停机";
        return status.substring(5) + "级告警";
    }
}

