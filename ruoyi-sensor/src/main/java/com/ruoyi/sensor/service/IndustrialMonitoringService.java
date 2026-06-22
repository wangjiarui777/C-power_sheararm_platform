package com.ruoyi.sensor.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import com.ruoyi.sensor.domain.DeviceTemperatureData;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.domain.entity.PhmAlarmEventEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmRuleEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEventEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.service.timeseries.TimeSeriesAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Read model for the three industrial monitoring workbenches.
 * All status, quality, threshold and health decisions are made here so every
 * frontend route observes the same result.
 */
@Service
public class IndustrialMonitoringService
{
    private static final long STALE_MILLIS = 30_000L;
    private static final long OFFLINE_MILLIS = 300_000L;

    @Autowired
    private PhmService phmService;

    @Autowired
    private IDeviceVibrationDataService vibrationService;

    @Autowired
    private IDeviceTemperatureDataService temperatureService;

    @Autowired
    private TimeSeriesAnalysisService timeSeriesAnalysisService;

    public List<Map<String, Object>> assetTree()
    {
        List<PhmDeviceEntity> devices = phmService.listDevices(null);
        Map<String, List<PhmDeviceEntity>> grouped = devices.stream()
                .collect(Collectors.groupingBy(item -> StringUtils.hasText(item.getOrgName())
                        ? item.getOrgName() : "未分组设备", LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<PhmDeviceEntity>> entry : grouped.entrySet())
        {
            Map<String, Object> org = new LinkedHashMap<>();
            org.put("id", "org:" + entry.getKey());
            org.put("label", entry.getKey());
            org.put("type", "organization");
            List<Map<String, Object>> children = entry.getValue().stream()
                    .map(this::deviceTreeNode)
                    .sorted(Comparator.comparingInt(item -> statusWeight(String.valueOf(item.get("status")))))
                    .collect(Collectors.toList());
            org.put("children", children);
            org.put("status", aggregateStatus(children));
            result.add(org);
        }
        return result;
    }

    public Map<String, Object> workbench(String requestedDeviceCode, Date from, Date to)
    {
        PhmDeviceEntity device = resolveDevice(requestedDeviceCode);
        Map<String, Object> result = new LinkedHashMap<>();
        if (device == null)
        {
            result.put("device", null);
            result.put("points", new ArrayList<>());
            result.put("alarms", new ArrayList<>());
            result.put("stateRail", new ArrayList<>());
            result.put("summary", emptySummary());
            return result;
        }

        List<Map<String, Object>> points = phmService.listMeasurePoints(device.getId()).stream()
                .filter(point -> !Boolean.FALSE.equals(point.getEnabled()))
                .map(point -> pointSnapshot(device, point, from, to))
                .sorted(Comparator.comparingInt(item -> statusWeight(String.valueOf(item.get("status")))))
                .collect(Collectors.toList());
        List<PhmAlarmEventEntity> alarms = activeAlarms(device.getDeviceCode());
        Date latest = points.stream()
                .map(item -> item.get("sampleTime"))
                .filter(Date.class::isInstance)
                .map(Date.class::cast)
                .max(Date::compareTo)
                .orElse(null);

        result.put("device", deviceSummary(device, points));
        result.put("points", points);
        result.put("alarms", alarms);
        result.put("stateRail", buildStateRail(device, alarms, latest));
        result.put("summary", buildSummary(device, points, alarms, latest));
        result.put("range", range(from, to));
        return result;
    }

    public Map<String, Object> pointTrend(Long pointId, Set<String> metrics, Date from, Date to, int maxPoints)
    {
        PhmMeasurePointEntity point = requirePoint(pointId);
        List<Map<String, Object>> rows = "temperature".equals(point.getSignalType())
                ? temperatureTrend(point, from, to)
                : vibrationTrend(point, from, to);
        List<Map<String, Object>> sampled = downsample(rows, Math.max(10, Math.min(maxPoints, 2000)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("point", point);
        result.put("metrics", metrics);
        result.put("rows", sampled);
        result.put("total", rows.size());
        result.put("sampled", sampled.size());
        return result;
    }

    public Map<String, Object> vibrationAnalysis(Long pointId, Date from, Date to, int maxPoints)
    {
        PhmMeasurePointEntity point = requirePoint(pointId);
        PhmDeviceEntity device = resolveDevice(point.getDeviceCode());
        List<Map<String, Object>> trend = downsample(vibrationTrend(point, from, to), maxPoints);
        DeviceVibrationData latest = latestVibration(point, from, to);
        Map<String, Object> frameData = loadLatestVibrationFrameData(point);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", device);
        result.put("point", point);
        result.put("snapshot", pointSnapshot(device, point, from, to));
        result.put("features", vibrationFeatures(latest, frameData));
        result.put("trend", trend);
        result.put("waveform", frameData.getOrDefault("waveform", new ArrayList<>()));
        result.put("frequencyAxis", frameData.getOrDefault("frequencyAxis", new ArrayList<>()));
        result.put("spectrum", frameData.getOrDefault("spectrum", new ArrayList<>()));
        result.put("envelopeSpectrum", new ArrayList<>());
        result.put("waterfall", frameData.getOrDefault("waterfall", new ArrayList<>()));
        result.put("thresholds", threshold(point, "vibration"));
        result.put("alarms", alarmsForPoint(point));
        boolean hasWaveform = !((List<?>) result.get("waveform")).isEmpty();
        boolean hasSpectrum = !((List<?>) result.get("spectrum")).isEmpty();
        result.put("dataStatus", hasWaveform || hasSpectrum ? "full" : trend.isEmpty() ? "empty" : "featureOnly");
        result.put("message", hasWaveform || hasSpectrum
                ? "已加载真实时域与频域数据"
                : "当前仅有低频特征趋势，尚未采集原始波形或频谱");
        return result;
    }

    public Map<String, Object> temperatureAnalysis(Long pointId, Date from, Date to, int maxPoints)
    {
        PhmMeasurePointEntity point = requirePoint(pointId);
        PhmDeviceEntity device = resolveDevice(point.getDeviceCode());
        List<Map<String, Object>> rows = enrichTemperature(temperatureTrend(point, from, to), point);
        List<Map<String, Object>> sampled = downsample(rows, maxPoints);
        Map<String, Object> snapshot = pointSnapshot(device, point, from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", device);
        result.put("point", point);
        result.put("snapshot", snapshot);
        result.put("trend", sampled);
        result.put("thresholds", threshold(point, "temperature"));
        result.put("events", phmService.listDeviceEvents(device == null ? null : device.getId(), point.getDeviceCode(), null));
        result.put("alarms", alarmsForPoint(point));
        result.put("statistics", temperatureStatistics(rows));
        result.put("dataStatus", rows.isEmpty() ? "empty" : "available");
        return result;
    }

    private Map<String, Object> deviceTreeNode(PhmDeviceEntity device)
    {
        List<PhmMeasurePointEntity> points = phmService.listMeasurePoints(device.getId()).stream()
                .filter(point -> !Boolean.FALSE.equals(point.getEnabled()))
                .collect(Collectors.toList());
        List<Map<String, Object>> pointNodes = points.stream().map(point -> {
            Map<String, Object> node = pointSnapshot(device, point, null, null);
            node.put("id", "point:" + point.getId());
            node.put("label", point.getPointName());
            node.put("type", "point");
            return node;
        }).collect(Collectors.toList());
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "device:" + device.getId());
        node.put("deviceId", device.getId());
        node.put("deviceCode", device.getDeviceCode());
        node.put("label", device.getDeviceName());
        node.put("type", "device");
        node.put("favorite", false);
        node.put("status", aggregateStatus(pointNodes));
        node.put("children", pointNodes);
        return node;
    }

    private Map<String, Object> pointSnapshot(PhmDeviceEntity device, PhmMeasurePointEntity point, Date from, Date to)
    {
        boolean temperature = "temperature".equals(point.getSignalType());
        Double value;
        Date sampleTime;
        String explicitQuality;
        if (temperature)
        {
            DeviceTemperatureData latest = latestTemperature(point, from, to);
            value = latest == null || latest.getTemperatureValue() == null ? null : latest.getTemperatureValue().doubleValue();
            sampleTime = latest == null ? null : latest.getCollectionTime();
            explicitQuality = latest == null ? null : latest.getQuality();
        }
        else
        {
            DeviceVibrationData latest = latestVibration(point, from, to);
            value = latest == null || latest.getVibrationValue() == null ? null : latest.getVibrationValue().doubleValue();
            sampleTime = latest == null ? null : latest.getSampleTime();
            explicitQuality = latest == null ? null : latest.getQuality();
        }
        Map<String, Object> thresholds = threshold(point, temperature ? "temperature" : "vibration");
        String quality = resolveQuality(explicitQuality, sampleTime);
        String status = resolveStatus(value, thresholds, quality, alarmsForPoint(point));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pointId", point.getId());
        result.put("pointCode", point.getPointCode());
        result.put("pointName", point.getPointName());
        result.put("deviceCode", point.getDeviceCode());
        result.put("channelId", point.getChannelId());
        result.put("signalType", point.getSignalType());
        result.put("metricCode", temperature ? "temperature" : "vibration");
        result.put("value", value);
        result.put("unit", StringUtils.hasText(point.getUnit()) ? point.getUnit() : temperature ? "℃" : "mm/s");
        result.put("sampleTime", sampleTime);
        result.put("quality", quality);
        result.put("status", status);
        result.put("health", health(value, thresholds, quality, status));
        result.put("thresholds", thresholds);
        result.put("openAlarmCount", alarmsForPoint(point).size());
        return result;
    }

    private DeviceVibrationData latestVibration(PhmMeasurePointEntity point, Date from, Date to)
    {
        DeviceVibrationData query = new DeviceVibrationData();
        query.setDeviceCode(point.getDeviceCode());
        query.setPointId(point.getId());
        query.setChannelId(point.getChannelId());
        List<DeviceVibrationData> rows = vibrationService.selectDeviceVibrationDataList(query);
        if (rows.isEmpty())
        {
            query.setPointId(null);
            rows = vibrationService.selectDeviceVibrationDataList(query);
        }
        return rows.stream()
                .filter(item -> inRange(item.getSampleTime(), from, to))
                .max(Comparator.comparing(DeviceVibrationData::getSampleTime, Comparator.nullsFirst(Date::compareTo)))
                .orElse(null);
    }

    private DeviceTemperatureData latestTemperature(PhmMeasurePointEntity point, Date from, Date to)
    {
        DeviceTemperatureData query = new DeviceTemperatureData();
        query.setDeviceCode(point.getDeviceCode());
        query.setPointId(point.getId());
        query.setChannelId(point.getChannelId());
        List<DeviceTemperatureData> exact = temperatureService.selectDeviceTemperatureDataList(query);
        if (exact.isEmpty())
        {
            query.setPointId(null);
            query.setChannelId(null);
            exact = temperatureService.selectDeviceTemperatureDataList(query);
        }
        return exact.stream()
                .filter(item -> inRange(item.getCollectionTime(), from, to))
                .max(Comparator.comparing(DeviceTemperatureData::getCollectionTime, Comparator.nullsFirst(Date::compareTo)))
                .orElse(null);
    }

    private List<Map<String, Object>> vibrationTrend(PhmMeasurePointEntity point, Date from, Date to)
    {
        DeviceVibrationData query = new DeviceVibrationData();
        query.setDeviceCode(point.getDeviceCode());
        query.setPointId(point.getId());
        query.setChannelId(point.getChannelId());
        List<DeviceVibrationData> rows = vibrationService.selectDeviceVibrationDataList(query);
        if (rows.isEmpty())
        {
            query.setPointId(null);
            rows = vibrationService.selectDeviceVibrationDataList(query);
        }
        return rows.stream()
                .filter(item -> inRange(item.getSampleTime(), from, to))
                .sorted(Comparator.comparing(DeviceVibrationData::getSampleTime))
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("time", item.getSampleTime());
                    row.put("vibration", decimal(item.getVibrationValue()));
                    row.put("temperature", decimal(item.getTemperatureValue()));
                    row.put("acceleration", decimal(item.getAccelerationValue()));
                    row.put("quality", resolveQuality(item.getQuality(), item.getSampleTime()));
                    return row;
                }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> temperatureTrend(PhmMeasurePointEntity point, Date from, Date to)
    {
        DeviceTemperatureData query = new DeviceTemperatureData();
        query.setDeviceCode(point.getDeviceCode());
        query.setPointId(point.getId());
        query.setChannelId(point.getChannelId());
        List<DeviceTemperatureData> rows = temperatureService.selectDeviceTemperatureDataList(query);
        if (rows.isEmpty())
        {
            query.setPointId(null);
            query.setChannelId(null);
            rows = temperatureService.selectDeviceTemperatureDataList(query);
        }
        return rows.stream()
                .filter(item -> inRange(item.getCollectionTime(), from, to))
                .sorted(Comparator.comparing(DeviceTemperatureData::getCollectionTime))
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("time", item.getCollectionTime());
                    row.put("temperature", decimal(item.getTemperatureValue()));
                    row.put("quality", resolveQuality(item.getQuality(), item.getCollectionTime()));
                    return row;
                }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> enrichTemperature(List<Map<String, Object>> rows, PhmMeasurePointEntity point)
    {
        List<Map<String, Object>> vibration = vibrationTrend(point, null, null);
        for (int i = 0; i < rows.size(); i++)
        {
            Map<String, Object> row = rows.get(i);
            Double current = number(row.get("temperature"));
            int start = Math.max(0, i - 4);
            List<Double> window = rows.subList(start, i + 1).stream()
                    .map(item -> number(item.get("temperature")))
                    .filter(value -> value != null)
                    .collect(Collectors.toList());
            row.put("ma", window.isEmpty() ? null : window.stream().mapToDouble(Double::doubleValue).average().orElse(0D));
            Double previous = i == 0 ? null : number(rows.get(i - 1).get("temperature"));
            Date time = (Date) row.get("time");
            Date previousTime = i == 0 ? null : (Date) rows.get(i - 1).get("time");
            if (current != null && previous != null && time != null && previousTime != null && time.after(previousTime))
            {
                double minutes = (time.getTime() - previousTime.getTime()) / 60000D;
                row.put("roc", (current - previous) / Math.max(minutes, 1D / 60D));
            }
            else
            {
                row.put("roc", null);
            }
            Map<String, Object> paired = nearest(vibration, time, 30_000L);
            row.put("vibration", paired == null ? null : paired.get("vibration"));
            row.put("couplingQuality", paired == null ? "GAP" : "ALIGNED");
        }
        return rows;
    }

    private Map<String, Object> nearest(List<Map<String, Object>> rows, Date target, long tolerance)
    {
        if (target == null)
        {
            return null;
        }
        return rows.stream()
                .filter(row -> row.get("time") instanceof Date)
                .min(Comparator.comparingLong(row -> Math.abs(((Date) row.get("time")).getTime() - target.getTime())))
                .filter(row -> Math.abs(((Date) row.get("time")).getTime() - target.getTime()) <= tolerance)
                .orElse(null);
    }

    private Map<String, Object> threshold(PhmMeasurePointEntity point, String featureCode)
    {
        PhmAlarmRuleEntity rule = phmService.listAlarmRules().stream()
                .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                .filter(item -> featureCode.equals(item.getFeatureCode())
                        || ("vibration".equals(featureCode) && "rms".equals(item.getFeatureCode())))
                .filter(item -> item.getPointId() == null || item.getPointId().equals(point.getId()))
                .filter(item -> item.getDeviceId() == null || item.getDeviceId().equals(point.getDeviceId()))
                .findFirst().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("high", rule == null ? null : decimal(rule.getHighLimit()));
        result.put("highHigh", rule == null ? null : decimal(rule.getHighHighLimit()));
        result.put("growthHigh", rule == null ? null : decimal(rule.getGrowthHighLimit()));
        result.put("growthHighHigh", rule == null ? null : decimal(rule.getGrowthHighHighLimit()));
        result.put("growthPeriod", rule == null ? null : rule.getGrowthPeriod());
        result.put("consecutiveCount", rule == null ? 1 : rule.getConsecutiveCount());
        result.put("ruleId", rule == null ? null : rule.getId());
        result.put("ruleName", rule == null ? "未配置规则" : rule.getRuleName());
        result.put("ruleVersion", rule == null || rule.getUpdateTime() == null ? "initial" : String.valueOf(rule.getUpdateTime().getTime()));
        result.put("actionAdvice", rule == null ? "请在配置管理中为该测点配置告警规则" : rule.getActionAdvice());
        return result;
    }

    private List<PhmAlarmEventEntity> alarmsForPoint(PhmMeasurePointEntity point)
    {
        return phmService.listAlarms(point.getDeviceCode(), null, null).stream()
                .filter(item -> item.getPointId() != null && item.getPointId().equals(point.getId()))
                .limit(20)
                .collect(Collectors.toList());
    }

    private List<PhmAlarmEventEntity> activeAlarms(String deviceCode)
    {
        return phmService.listAlarms(deviceCode, null, null).stream()
                .filter(item -> !"CLOSED".equals(item.getWorkflowStatus())
                        && !"handled".equals(item.getStatus())
                        && !"ignored".equals(item.getStatus()))
                .collect(Collectors.toList());
    }

    private Map<String, Object> loadLatestVibrationFrameData(PhmMeasurePointEntity point)
    {
        try
        {
            return timeSeriesAnalysisService.loadDiagnosisData(point.getDeviceCode(), point.getChannelId(), 4096, 2048);
        }
        catch (Exception ignored)
        {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> vibrationFeatures(DeviceVibrationData latest, Map<String, Object> frameData)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rms", latest == null ? null : decimal(latest.getVibrationValue()));
        result.put("acceleration", latest == null ? null : decimal(latest.getAccelerationValue()));
        result.put("peak", null);
        result.put("peakToPeak", null);
        result.put("kurtosis", null);
        result.put("crestFactor", null);
        result.put("mainFrequency", dominantFrequency(frameData));
        result.put("speedOrder", null);
        return result;
    }

    private Double dominantFrequency(Map<String, Object> frameData)
    {
        Object frequencies = frameData.get("frequencyAxis");
        Object spectrum = frameData.get("spectrum");
        if (!(frequencies instanceof List) || !(spectrum instanceof List))
        {
            return null;
        }
        List<?> x = (List<?>) frequencies;
        List<?> y = (List<?>) spectrum;
        int limit = Math.min(x.size(), y.size());
        int maxIndex = -1;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < limit; i++)
        {
            Double value = number(y.get(i));
            if (value != null && value > max)
            {
                max = value;
                maxIndex = i;
            }
        }
        return maxIndex < 0 ? null : number(x.get(maxIndex));
    }

    private Map<String, Object> temperatureStatistics(List<Map<String, Object>> rows)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maximum", rows.stream().map(row -> number(row.get("temperature")))
                .filter(value -> value != null).max(Double::compareTo).orElse(null));
        result.put("maxRoc", rows.stream().map(row -> number(row.get("roc")))
                .filter(value -> value != null).max(Double::compareTo).orElse(null));
        result.put("latestRoc", rows.isEmpty() ? null : rows.get(rows.size() - 1).get("roc"));
        result.put("latestVibration", rows.isEmpty() ? null : rows.get(rows.size() - 1).get("vibration"));
        return result;
    }

    private Map<String, Object> deviceSummary(PhmDeviceEntity device, List<Map<String, Object>> points)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", device.getId());
        result.put("deviceCode", device.getDeviceCode());
        result.put("deviceName", device.getDeviceName());
        result.put("deviceType", device.getDeviceType());
        result.put("organization", device.getOrgName());
        result.put("line", device.getLocation());
        result.put("status", aggregateStatus(points));
        result.put("health", points.stream().map(item -> number(item.get("health")))
                .filter(value -> value != null).mapToDouble(Double::doubleValue).average()
                .orElse(Optional.ofNullable(device.getHealthIndex()).orElse(0)));
        return result;
    }

    private Map<String, Object> buildSummary(PhmDeviceEntity device, List<Map<String, Object>> points,
                                             List<PhmAlarmEventEntity> alarms, Date latest)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlineDevices", points.stream().anyMatch(item -> !"OFFLINE".equals(item.get("quality"))) ? 1 : 0);
        result.put("abnormalDevices", points.stream().anyMatch(item -> "ALARM".equals(item.get("status"))) ? 1 : 0);
        result.put("unacknowledgedAlarms", alarms.stream()
                .filter(item -> item.getWorkflowStatus() == null || "NEW".equals(item.getWorkflowStatus())).count());
        result.put("dataDelaySeconds", latest == null ? null : Math.max(0L, (System.currentTimeMillis() - latest.getTime()) / 1000L));
        result.put("latestSampleTime", latest);
        return result;
    }

    private List<Map<String, Object>> buildStateRail(PhmDeviceEntity device, List<PhmAlarmEventEntity> alarms, Date latest)
    {
        List<Map<String, Object>> result = alarms.stream().map(alarm -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", alarm.getAlarmTime());
            item.put("type", alarm.getConditionStatus() != null && "RETURNED_TO_NORMAL".equals(alarm.getConditionStatus())
                    ? "recovered" : alarm.getAlarmLevel() != null && alarm.getAlarmLevel() >= 3 ? "alarm" : "warning");
            item.put("label", alarm.getPointName());
            item.put("alarmId", alarm.getId());
            return item;
        }).collect(Collectors.toList());
        if (latest == null || System.currentTimeMillis() - latest.getTime() > OFFLINE_MILLIS)
        {
            Map<String, Object> offline = new LinkedHashMap<>();
            offline.put("time", latest);
            offline.put("type", "offline");
            offline.put("label", device.getDeviceName() + " 数据中断");
            result.add(offline);
        }
        return result.stream().sorted(Comparator.comparing(item -> (Date) item.get("time"),
                Comparator.nullsLast(Date::compareTo))).collect(Collectors.toList());
    }

    private Map<String, Object> emptySummary()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlineDevices", 0);
        result.put("abnormalDevices", 0);
        result.put("unacknowledgedAlarms", 0);
        result.put("dataDelaySeconds", null);
        return result;
    }

    private Map<String, Object> range(Date from, Date to)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        return result;
    }

    private PhmDeviceEntity resolveDevice(String deviceCode)
    {
        List<PhmDeviceEntity> devices = phmService.listDevices(deviceCode);
        if (StringUtils.hasText(deviceCode))
        {
            return devices.stream().filter(item -> deviceCode.equals(item.getDeviceCode())).findFirst().orElse(null);
        }
        return devices.stream().findFirst().orElse(null);
    }

    private PhmMeasurePointEntity requirePoint(Long pointId)
    {
        return phmService.listMeasurePoints(null).stream()
                .filter(item -> item.getId().equals(pointId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("测点不存在: " + pointId));
    }

    private List<Map<String, Object>> downsample(List<Map<String, Object>> rows, int maxPoints)
    {
        if (rows.size() <= maxPoints)
        {
            return rows;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        double step = (double) (rows.size() - 1) / (double) (maxPoints - 1);
        for (int i = 0; i < maxPoints; i++)
        {
            result.add(rows.get((int) Math.round(i * step)));
        }
        return result;
    }

    private boolean inRange(Date value, Date from, Date to)
    {
        return value != null && (from == null || !value.before(from)) && (to == null || !value.after(to));
    }

    private String resolveQuality(String explicit, Date sampleTime)
    {
        if (StringUtils.hasText(explicit) && !"GOOD".equalsIgnoreCase(explicit))
        {
            return explicit.toUpperCase();
        }
        if (sampleTime == null)
        {
            return "OFFLINE";
        }
        long age = Math.max(0L, System.currentTimeMillis() - sampleTime.getTime());
        if (age > OFFLINE_MILLIS) return "OFFLINE";
        if (age > STALE_MILLIS) return "STALE";
        return "GOOD";
    }

    private String resolveStatus(Double value, Map<String, Object> thresholds, String quality,
                                 List<PhmAlarmEventEntity> alarms)
    {
        if (!alarms.isEmpty() && alarms.stream().anyMatch(item ->
                !"CLOSED".equals(item.getWorkflowStatus())
                        && !"handled".equals(item.getStatus())
                        && !"ignored".equals(item.getStatus())))
        {
            return alarms.stream().anyMatch(item -> Optional.ofNullable(item.getAlarmLevel()).orElse(1) >= 3)
                    ? "ALARM" : "WARNING";
        }
        if ("OFFLINE".equals(quality) || "BAD".equals(quality))
        {
            return "UNKNOWN";
        }
        Double high = number(thresholds.get("high"));
        Double highHigh = number(thresholds.get("highHigh"));
        if (value != null && highHigh != null && value >= highHigh) return "ALARM";
        if (value != null && high != null && value >= high) return "WARNING";
        return "NORMAL";
    }

    private int health(Double value, Map<String, Object> thresholds, String quality, String status)
    {
        if ("OFFLINE".equals(quality) || "BAD".equals(quality))
        {
            return 0;
        }
        Double high = number(thresholds.get("high"));
        Double highHigh = number(thresholds.get("highHigh"));
        if (value == null || high == null || high <= 0)
        {
            return "ALARM".equals(status) ? 40 : "WARNING".equals(status) ? 70 : 100;
        }
        double denominator = highHigh != null && highHigh > high ? highHigh : high * 1.5D;
        int score = (int) Math.round(100D - Math.min(1D, value / denominator) * 55D);
        if ("ALARM".equals(status)) score = Math.min(score, 45);
        if ("WARNING".equals(status)) score = Math.min(score, 70);
        return Math.max(0, Math.min(100, score));
    }

    private String aggregateStatus(List<Map<String, Object>> rows)
    {
        if (rows.stream().anyMatch(item -> "ALARM".equals(item.get("status")))) return "ALARM";
        if (rows.stream().anyMatch(item -> "WARNING".equals(item.get("status")))) return "WARNING";
        if (rows.stream().allMatch(item -> "UNKNOWN".equals(item.get("status")))) return "UNKNOWN";
        return "NORMAL";
    }

    private int statusWeight(String status)
    {
        if ("ALARM".equalsIgnoreCase(status)) return 0;
        if ("WARNING".equalsIgnoreCase(status)) return 1;
        if ("UNKNOWN".equalsIgnoreCase(status)) return 2;
        return 3;
    }

    private Double decimal(BigDecimal value)
    {
        return value == null ? null : value.doubleValue();
    }

    private Double number(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).doubleValue();
        }
        if (value == null)
        {
            return null;
        }
        try
        {
            return Double.valueOf(String.valueOf(value));
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}
