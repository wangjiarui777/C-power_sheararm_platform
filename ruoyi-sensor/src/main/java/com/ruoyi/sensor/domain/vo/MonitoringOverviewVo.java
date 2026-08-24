package com.ruoyi.sensor.domain.vo;

import java.util.List;
import java.util.Map;
import com.ruoyi.sensor.domain.entity.PhmAlarmEventEntity;

public class MonitoringOverviewVo
{
    private Integer deviceCount;
    private Double latestVibration;
    private Double latestTemperature;
    private String updateTime;
    private TrendVo vibrationTrend;
    private TrendVo temperatureTrend;
    private List<DevicePointVo> devicePoints;
    private Double vibrationThreshold;
    private Double temperatureThreshold;
    /** Multi-device homepage read model. Existing fields above remain for legacy consumers. */
    private Map<String, Object> summary;
    private List<Map<String, Object>> devices;
    private List<PhmAlarmEventEntity> alarms;

    public Integer getDeviceCount() { return deviceCount; }
    public void setDeviceCount(Integer deviceCount) { this.deviceCount = deviceCount; }
    public Double getLatestVibration() { return latestVibration; }
    public void setLatestVibration(Double latestVibration) { this.latestVibration = latestVibration; }
    public Double getLatestTemperature() { return latestTemperature; }
    public void setLatestTemperature(Double latestTemperature) { this.latestTemperature = latestTemperature; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
    public TrendVo getVibrationTrend() { return vibrationTrend; }
    public void setVibrationTrend(TrendVo vibrationTrend) { this.vibrationTrend = vibrationTrend; }
    public TrendVo getTemperatureTrend() { return temperatureTrend; }
    public void setTemperatureTrend(TrendVo temperatureTrend) { this.temperatureTrend = temperatureTrend; }
    public List<DevicePointVo> getDevicePoints() { return devicePoints; }
    public void setDevicePoints(List<DevicePointVo> devicePoints) { this.devicePoints = devicePoints; }
    public Double getVibrationThreshold() { return vibrationThreshold; }
    public void setVibrationThreshold(Double vibrationThreshold) { this.vibrationThreshold = vibrationThreshold; }
    public Double getTemperatureThreshold() { return temperatureThreshold; }
    public void setTemperatureThreshold(Double temperatureThreshold) { this.temperatureThreshold = temperatureThreshold; }
    public Map<String, Object> getSummary() { return summary; }
    public void setSummary(Map<String, Object> summary) { this.summary = summary; }
    public List<Map<String, Object>> getDevices() { return devices; }
    public void setDevices(List<Map<String, Object>> devices) { this.devices = devices; }
    public List<PhmAlarmEventEntity> getAlarms() { return alarms; }
    public void setAlarms(List<PhmAlarmEventEntity> alarms) { this.alarms = alarms; }

    public static class TrendVo
    {
        private List<String> xData;
        private List<Double> yData;
        public List<String> getxData() { return xData; }
        public void setxData(List<String> xData) { this.xData = xData; }
        public List<Double> getyData() { return yData; }
        public void setyData(List<Double> yData) { this.yData = yData; }
    }

    public static class DevicePointVo
    {
        private String deviceCode;
        private Double vibrationValue;
        private Double temperatureValue;
        public String getDeviceCode() { return deviceCode; }
        public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
        public Double getVibrationValue() { return vibrationValue; }
        public void setVibrationValue(Double vibrationValue) { this.vibrationValue = vibrationValue; }
        public Double getTemperatureValue() { return temperatureValue; }
        public void setTemperatureValue(Double temperatureValue) { this.temperatureValue = temperatureValue; }
    }
}

