package com.ruoyi.sensor.web;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.domain.entity.PhmAlarmEventEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmRuleEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.service.IDeviceVibrationDataService;
import com.ruoyi.sensor.service.PhmService;

@RestController
@RequestMapping({"/sensor/vibration-data", "/system/vibration"})
public class DeviceVibrationDataController extends BaseController
{
    private static final String[] CHANNEL_NAMES = {
            "驱动端水平振动", "驱动端垂直振动", "驱动端轴向振动", "非驱动端水平振动",
            "非驱动端垂直振动", "非驱动端轴向振动", "减速机输入端", "减速机输出端"
    };

    @Autowired
    private IDeviceVibrationDataService deviceVibrationDataService;

    @Autowired
    private PhmService phmService;

    @Value("${sensor.default-device-code:}")
    private String defaultDeviceCode;

    @PreAuthorize("@ss.hasPermi('sensor:vibration:list')")
    @GetMapping("/list")
    public TableDataInfo list(DeviceVibrationData deviceVibrationData)
    {
        startPage();
        List<DeviceVibrationData> list = scopedRows(
                deviceVibrationDataService.selectDeviceVibrationDataList(deviceVibrationData));
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('sensor:vibration:list')")
    @GetMapping("/recent")
    public AjaxResult recent()
    {
        return success(scopedRows(deviceVibrationDataService.selectRecentDeviceVibrationDataList()));
    }

    @PreAuthorize("@ss.hasPermi('sensor:monitoring:view')")
    @GetMapping("/multi-channel/overview")
    public AjaxResult multiChannelOverview(@RequestParam(required = false) String deviceCode,
                                           @RequestParam(defaultValue = "30") Integer windowMinutes)
    {
        String safeDeviceCode = normalizeDeviceCode(deviceCode);
        PhmDeviceEntity device = findDevice(safeDeviceCode);
        List<DeviceVibrationData> records = device == null ? new ArrayList<>() : loadRecentRecords(safeDeviceCode);
        Map<Integer, List<DeviceVibrationData>> byChannel = groupByChannel(records);
        Map<Integer, PhmMeasurePointEntity> points = loadPointMap(device);
        Map<Integer, PhmAlarmRuleEntity> rules = loadRuleMap(device, points);
        Map<Integer, PhmAlarmEventEntity> openAlarms = loadOpenAlarmMap(safeDeviceCode, points);

        List<Map<String, Object>> channels = new ArrayList<>();
        Date latestSampleTime = null;
        int maxAlarmLevel = 0;
        int onlineCount = 0;
        for (int channelId = 1; channelId <= 8; channelId++)
        {
            Map<String, Object> channel = buildChannelOverview(channelId, byChannel.get(channelId), points.get(channelId), rules.get(channelId), openAlarms.get(channelId));
            channels.add(channel);
            Date sampleTime = (Date) channel.get("sampleTimeRaw");
            if (sampleTime != null && (latestSampleTime == null || sampleTime.after(latestSampleTime)))
            {
                latestSampleTime = sampleTime;
            }
            maxAlarmLevel = Math.max(maxAlarmLevel, toInt(channel.get("alarmLevel"), 0));
            if (!"offline".equals(channel.get("freshness")))
            {
                onlineCount++;
            }
            channel.remove("sampleTimeRaw");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device", buildDeviceSummary(device, safeDeviceCode));
        data.put("channels", channels);
        data.put("summary", buildOverviewSummary(channels, latestSampleTime, maxAlarmLevel, onlineCount, windowMinutes));
        data.put("windowMinutes", windowMinutes);
        return success(data);
    }

    @PreAuthorize("@ss.hasPermi('sensor:monitoring:view')")
    @GetMapping("/multi-channel/{channelId}/analysis")
    public AjaxResult channelAnalysis(@PathVariable("channelId") Integer channelId,
                                      @RequestParam(required = false) String deviceCode)
    {
        int safeChannelId = channelId == null || channelId < 1 || channelId > 8 ? 1 : channelId;
        String safeDeviceCode = normalizeDeviceCode(deviceCode);
        PhmDeviceEntity device = findDevice(safeDeviceCode);
        List<DeviceVibrationData> records = (device == null ? new ArrayList<DeviceVibrationData>() : loadRecentRecords(safeDeviceCode)).stream()
                .filter(item -> item.getChannelId() != null && item.getChannelId().intValue() == safeChannelId)
                .sorted(Comparator.comparing(DeviceVibrationData::getSampleTime))
                .collect(Collectors.toList());

        Map<Integer, PhmMeasurePointEntity> points = loadPointMap(device);
        Map<Integer, PhmAlarmRuleEntity> rules = loadRuleMap(device, points);
        Map<Integer, PhmAlarmEventEntity> openAlarms = loadOpenAlarmMap(safeDeviceCode, points);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceCode", safeDeviceCode);
        data.put("channelId", safeChannelId);
        data.put("channelName", resolveChannelName(safeChannelId, points.get(safeChannelId)));
        data.put("trend", buildTrend(records));
        data.put("waveform", new ArrayList<>());
        data.put("spectrum", new ArrayList<>());
        data.put("waterfall", new ArrayList<>());
        data.put("thresholds", buildThresholds(rules.get(safeChannelId)));
        data.put("events", buildEvents(openAlarms.get(safeChannelId)));
        data.put("dataStatus", records.isEmpty() ? "noRecentData" : "recentOnly");
        data.put("message", "当前通道暂无高频波形/频谱/瀑布数据，已展示最近趋势数据。");
        return success(data);
    }

    private String normalizeDeviceCode(String deviceCode)
    {
        return deviceCode == null || deviceCode.trim().isEmpty()
                ? (defaultDeviceCode == null ? "" : defaultDeviceCode) : deviceCode.trim();
    }

    private List<DeviceVibrationData> loadRecentRecords(String deviceCode)
    {
        return scopedRows(deviceVibrationDataService.selectRecentDeviceVibrationDataList()).stream()
                .filter(item -> deviceCode.equals(item.getDeviceCode()))
                .collect(Collectors.toList());
    }

    private List<DeviceVibrationData> scopedRows(List<DeviceVibrationData> rows)
    {
        Set<String> accessibleCodes = accessibleDeviceCodes();
        return rows == null ? new ArrayList<>() : rows.stream()
                .filter(item -> item != null && accessibleCodes.contains(item.getDeviceCode()))
                .collect(Collectors.toList());
    }

    private Set<String> accessibleDeviceCodes()
    {
        return phmService.listDevices(null).stream()
                .map(PhmDeviceEntity::getDeviceCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
    }

    private Map<Integer, List<DeviceVibrationData>> groupByChannel(List<DeviceVibrationData> records)
    {
        return records.stream()
                .filter(item -> item.getChannelId() != null)
                .collect(Collectors.groupingBy(DeviceVibrationData::getChannelId));
    }

    private PhmDeviceEntity findDevice(String deviceCode)
    {
        return phmService.listDevices(null).stream()
                .filter(item -> deviceCode.equals(item.getDeviceCode()))
                .findFirst()
                .orElse(null);
    }

    private Map<Integer, PhmMeasurePointEntity> loadPointMap(PhmDeviceEntity device)
    {
        Map<Integer, PhmMeasurePointEntity> map = new HashMap<>();
        if (device == null || device.getId() == null)
        {
            return map;
        }
        for (PhmMeasurePointEntity point : phmService.listMeasurePoints(device.getId()))
        {
            if (point.getChannelId() != null)
            {
                map.put(point.getChannelId(), point);
            }
        }
        return map;
    }

    private Map<Integer, PhmAlarmRuleEntity> loadRuleMap(PhmDeviceEntity device, Map<Integer, PhmMeasurePointEntity> points)
    {
        Map<Integer, PhmAlarmRuleEntity> map = new HashMap<>();
        List<PhmAlarmRuleEntity> rules = phmService.listAlarmRules();
        for (int channelId = 1; channelId <= 8; channelId++)
        {
            PhmMeasurePointEntity point = points.get(channelId);
            PhmAlarmRuleEntity matched = rules.stream()
                    .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                    .filter(rule -> rule.getFeatureCode() == null || "vibration".equals(rule.getFeatureCode()) || "rms".equals(rule.getFeatureCode()))
                    .filter(rule -> rule.getPointId() == null || (point != null && rule.getPointId().equals(point.getId())))
                    .filter(rule -> rule.getDeviceId() == null || (device != null && rule.getDeviceId().equals(device.getId())))
                    .findFirst()
                    .orElse(null);
            if (matched != null)
            {
                map.put(channelId, matched);
            }
        }
        return map;
    }

    private Map<Integer, PhmAlarmEventEntity> loadOpenAlarmMap(String deviceCode, Map<Integer, PhmMeasurePointEntity> points)
    {
        Map<Integer, PhmAlarmEventEntity> map = new HashMap<>();
        List<PhmAlarmEventEntity> alarms = phmService.listAlarms(deviceCode, null, null).stream()
                .filter(item -> !"handled".equals(item.getStatus()) && !"ignored".equals(item.getStatus()))
                .sorted(Comparator.comparing(PhmAlarmEventEntity::getAlarmTime, Comparator.nullsLast(Date::compareTo)).reversed())
                .collect(Collectors.toList());
        for (int channelId = 1; channelId <= 8; channelId++)
        {
            PhmMeasurePointEntity point = points.get(channelId);
            PhmAlarmEventEntity alarm = alarms.stream()
                    .filter(item -> point != null && item.getPointId() != null && item.getPointId().equals(point.getId()))
                    .findFirst()
                    .orElse(null);
            if (alarm != null)
            {
                map.put(channelId, alarm);
            }
        }
        return map;
    }

    private Map<String, Object> buildChannelOverview(int channelId, List<DeviceVibrationData> channelRecords,
                                                     PhmMeasurePointEntity point, PhmAlarmRuleEntity rule,
                                                     PhmAlarmEventEntity openAlarm)
    {
        List<DeviceVibrationData> records = channelRecords == null ? new ArrayList<>() : channelRecords.stream()
                .sorted(Comparator.comparing(DeviceVibrationData::getSampleTime))
                .collect(Collectors.toList());
        DeviceVibrationData latest = records.isEmpty() ? null : records.get(records.size() - 1);
        Double rms = latest == null || latest.getVibrationValue() == null ? null : latest.getVibrationValue().doubleValue();
        Double temp = latest == null || latest.getTemperatureValue() == null ? null : latest.getTemperatureValue().doubleValue();
        Date sampleTime = latest == null ? null : latest.getSampleTime();
        Map<String, Object> thresholds = buildThresholds(rule);
        double high = toDouble(thresholds.get("highLimit"), 4D);
        double highHigh = toDouble(thresholds.get("highHighLimit"), 6D);
        int alarmLevel = openAlarm == null || openAlarm.getAlarmLevel() == null ? 0 : openAlarm.getAlarmLevel();
        if (rms != null && rms >= highHigh)
        {
            alarmLevel = Math.max(alarmLevel, 3);
        }
        else if (rms != null && rms >= high)
        {
            alarmLevel = Math.max(alarmLevel, 2);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("channelId", channelId);
        map.put("channelName", resolveChannelName(channelId, point));
        map.put("pointId", point == null ? null : point.getId());
        map.put("pointName", point == null ? null : point.getPointName());
        map.put("rms", rms);
        map.put("peak", rms == null ? null : rms * 1.25D);
        map.put("peakToPeak", rms == null ? null : rms * 1.85D);
        map.put("temperature", temp);
        map.put("health", calcHealth(rms, temp, alarmLevel, sampleTime));
        map.put("alarmLevel", alarmLevel);
        map.put("status", resolveStatus(alarmLevel, sampleTime));
        map.put("freshness", resolveFreshness(sampleTime));
        map.put("sampleTime", sampleTime);
        map.put("sampleTimeRaw", sampleTime);
        map.put("thresholds", thresholds);
        map.put("alarmNo", openAlarm == null ? null : openAlarm.getAlarmNo());
        map.put("diagnosis", openAlarm == null ? buildNormalDiagnosis(rms) : openAlarm.getDiagnosisResult());
        map.put("trend", buildTrend(records));
        return map;
    }

    private Map<String, Object> buildDeviceSummary(PhmDeviceEntity device, String deviceCode)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deviceCode", deviceCode);
        map.put("deviceName", device == null ? "主扇风机" : device.getDeviceName());
        map.put("deviceType", device == null ? "Rotating machinery" : device.getDeviceType());
        map.put("runStatus", device == null ? "running" : device.getStatus());
        map.put("healthIndex", device == null ? null : device.getHealthIndex());
        return map;
    }

    private Map<String, Object> buildOverviewSummary(List<Map<String, Object>> channels, Date latestSampleTime,
                                                     int maxAlarmLevel, int onlineCount, Integer windowMinutes)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("latestSampleTime", latestSampleTime);
        map.put("maxAlarmLevel", maxAlarmLevel);
        map.put("onlineCount", onlineCount);
        map.put("channelCount", 8);
        map.put("windowMinutes", windowMinutes);
        map.put("connectionState", onlineCount > 0 ? "online" : "offline");
        map.put("highestRiskChannel", channels.stream()
                .max(Comparator.comparing(item -> toInt(item.get("alarmLevel"), 0)))
                .map(item -> item.get("channelId"))
                .orElse(null));
        return map;
    }

    private List<Map<String, Object>> buildTrend(List<DeviceVibrationData> records)
    {
        return records.stream().map(item -> {
            Map<String, Object> point = new LinkedHashMap<>();
            Double rms = item.getVibrationValue() == null ? null : item.getVibrationValue().doubleValue();
            point.put("time", item.getSampleTime());
            point.put("rms", rms);
            point.put("temperature", item.getTemperatureValue() == null ? null : item.getTemperatureValue().doubleValue());
            point.put("peak", rms == null ? null : rms * 1.25D);
            return point;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildEvents(PhmAlarmEventEntity alarm)
    {
        List<Map<String, Object>> events = new ArrayList<>();
        if (alarm != null)
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alarmNo", alarm.getAlarmNo());
            item.put("alarmTime", alarm.getAlarmTime());
            item.put("alarmLevel", alarm.getAlarmLevel());
            item.put("diagnosisResult", alarm.getDiagnosisResult());
            item.put("status", alarm.getStatus());
            events.add(item);
        }
        return events;
    }

    private Map<String, Object> buildThresholds(PhmAlarmRuleEntity rule)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("highLimit", rule == null || rule.getHighLimit() == null ? 4D : rule.getHighLimit().doubleValue());
        map.put("highHighLimit", rule == null || rule.getHighHighLimit() == null ? 6D : rule.getHighHighLimit().doubleValue());
        map.put("ruleName", rule == null ? "ISO 10816 兜底阈值" : rule.getRuleName());
        map.put("actionAdvice", rule == null ? "持续观察趋势，超过高高限时安排停机检查。" : rule.getActionAdvice());
        return map;
    }

    private String resolveChannelName(int channelId, PhmMeasurePointEntity point)
    {
        if (point != null && point.getPointName() != null && !point.getPointName().trim().isEmpty())
        {
            return point.getPointName();
        }
        return CHANNEL_NAMES[Math.max(0, Math.min(CHANNEL_NAMES.length - 1, channelId - 1))];
    }

    private String resolveStatus(int alarmLevel, Date sampleTime)
    {
        if ("offline".equals(resolveFreshness(sampleTime)))
        {
            return "offline";
        }
        if (alarmLevel >= 3)
        {
            return "alarm";
        }
        if (alarmLevel >= 2)
        {
            return "warning";
        }
        return "normal";
    }

    private String resolveFreshness(Date sampleTime)
    {
        if (sampleTime == null)
        {
            return "offline";
        }
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - sampleTime.getTime()) / 1000L);
        if (ageSeconds <= 30)
        {
            return "realtime";
        }
        if (ageSeconds <= 300)
        {
            return "delayed";
        }
        return "offline";
    }

    private int calcHealth(Double rms, Double temp, int alarmLevel, Date sampleTime)
    {
        if ("offline".equals(resolveFreshness(sampleTime)))
        {
            return 0;
        }
        double rmsScore = rms == null ? 0.5D : Math.max(0D, Math.min(1D, 1D - rms / 8D));
        double tempScore = temp == null ? 0.5D : Math.max(0D, Math.min(1D, 1D - Math.max(0D, temp - 60D) / 70D));
        int score = (int) Math.round((rmsScore * 0.65D + tempScore * 0.35D) * 100D);
        if (alarmLevel >= 3)
        {
            return Math.min(score, 45);
        }
        if (alarmLevel >= 2)
        {
            return Math.min(score, 70);
        }
        return score;
    }

    private String buildNormalDiagnosis(Double rms)
    {
        if (rms == null)
        {
            return "暂无最近采样";
        }
        if (rms >= 6D)
        {
            return "振动超过高高限，建议立即复核轴承、联轴器和基础松动。";
        }
        if (rms >= 4D)
        {
            return "振动接近告警区间，建议观察趋势并安排点检。";
        }
        return "振动处于可接受范围。";
    }

    private int toInt(Object value, int defaultValue)
    {
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private double toDouble(Object value, double defaultValue)
    {
        if (value instanceof Number)
        {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    @PreAuthorize("@ss.hasPermi('sensor:vibration:export')")
    @Log(title = "vibration data", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DeviceVibrationData deviceVibrationData)
    {
        List<DeviceVibrationData> list = scopedRows(
                deviceVibrationDataService.selectDeviceVibrationDataList(deviceVibrationData));
        ExcelUtil<DeviceVibrationData> util = new ExcelUtil<DeviceVibrationData>(DeviceVibrationData.class);
        util.exportCsv(response, list, "vibration_data");
    }

    @PreAuthorize("@ss.hasPermi('sensor:vibration:query')")
    @GetMapping(value = "/{dataId}")
    public AjaxResult getInfo(@PathVariable("dataId") Long dataId)
    {
        DeviceVibrationData data = deviceVibrationDataService.selectDeviceVibrationDataById(dataId);
        return success(data != null && accessibleDeviceCodes().contains(data.getDeviceCode()) ? data : null);
    }

    @PreAuthorize("@ss.hasPermi('sensor:vibration:add')")
    @Log(title = "vibration data", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody DeviceVibrationData deviceVibrationData)
    {
        if (deviceVibrationData == null || !accessibleDeviceCodes().contains(deviceVibrationData.getDeviceCode()))
        {
            return error("设备不存在或无权访问");
        }
        deviceVibrationData.setCreateBy(getUsername());
        return toAjax(deviceVibrationDataService.insertDeviceVibrationData(deviceVibrationData));
    }

    @PreAuthorize("@ss.hasPermi('sensor:vibration:edit')")
    @Log(title = "vibration data", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody DeviceVibrationData deviceVibrationData)
    {
        DeviceVibrationData existing = deviceVibrationData == null || deviceVibrationData.getDataId() == null
                ? null : deviceVibrationDataService.selectDeviceVibrationDataById(deviceVibrationData.getDataId());
        String existingCode = existing == null ? null : existing.getDeviceCode();
        if (deviceVibrationData == null
                || (existing != null && !accessibleDeviceCodes().contains(existingCode))
                || (deviceVibrationData.getDeviceCode() != null
                    && !accessibleDeviceCodes().contains(deviceVibrationData.getDeviceCode())))
        {
            return error("设备不存在或无权访问");
        }
        deviceVibrationData.setUpdateBy(getUsername());
        return toAjax(deviceVibrationDataService.updateDeviceVibrationData(deviceVibrationData));
    }

    @PreAuthorize("@ss.hasPermi('sensor:vibration:remove')")
    @Log(title = "vibration data", businessType = BusinessType.DELETE)
    @DeleteMapping("/{dataIds}")
    public AjaxResult remove(@PathVariable Long[] dataIds)
    {
        if (dataIds == null)
        {
            return error("数据不能为空");
        }
        Set<String> accessibleCodes = accessibleDeviceCodes();
        for (Long dataId : dataIds)
        {
            DeviceVibrationData data = deviceVibrationDataService.selectDeviceVibrationDataById(dataId);
            if (data == null || !accessibleCodes.contains(data.getDeviceCode()))
            {
                return error("包含不存在或无权访问的数据");
            }
        }
        return toAjax(deviceVibrationDataService.deleteDeviceVibrationDataByIds(dataIds));
    }
}

