package com.ruoyi.sensor.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.ruoyi.sensor.domain.DeviceTemperatureData;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.domain.dto.PhmAlarmActionRequest;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmEventEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmHandleRecordEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmRuleEntity;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEventEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceFavoriteEntity;
import com.ruoyi.sensor.domain.entity.PhmFeatureConfigEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.entity.PhmSystemConfigEntity;
import com.ruoyi.sensor.domain.vo.PhmTrendPointVo;
import com.ruoyi.sensor.domain.vo.PhmHistoryReportVo;
import com.ruoyi.sensor.domain.vo.PhmRealtimeReportVo;
import com.ruoyi.sensor.mapper.EnhancedInferenceRecordMapper;
import com.ruoyi.sensor.mapper.PhmAlarmEventMapper;
import com.ruoyi.sensor.mapper.PhmAlarmHandleRecordMapper;
import com.ruoyi.sensor.mapper.PhmAlarmRuleMapper;
import com.ruoyi.sensor.mapper.PhmAttachmentMapper;
import com.ruoyi.sensor.mapper.PhmDeviceEventMapper;
import com.ruoyi.sensor.mapper.PhmDeviceFavoriteMapper;
import com.ruoyi.sensor.mapper.PhmDeviceMapper;
import com.ruoyi.sensor.mapper.PhmFeatureConfigMapper;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import com.ruoyi.sensor.mapper.PhmSystemConfigMapper;
import com.ruoyi.sensor.service.IDeviceTemperatureDataService;
import com.ruoyi.sensor.service.IDeviceVibrationDataService;
import com.ruoyi.sensor.service.PhmService;
import com.ruoyi.sensor.service.support.PhmDeviceEventPolicy;
import com.ruoyi.sensor.service.support.PhmDiagnosisLinkagePolicy;
import com.ruoyi.sensor.websocket.SensorWebSocketHandler;

@Service
public class PhmServiceImpl implements PhmService
{
    private static final String STATUS_UNHANDLED = "unhandled";
    private static final String STATUS_HANDLED = "handled";
    private static final String STATUS_IGNORED = "ignored";
    private static final String EVENT_TYPE_DIAGNOSIS = "diagnosis";
    private static final String EVENT_TYPE_ALARM_HANDLE = "alarm_handle";

    @Autowired
    private EnhancedInferenceRecordMapper enhancedInferenceRecordMapper;

    @Autowired
    private PhmDeviceMapper deviceMapper;

    @Autowired
    private PhmMeasurePointMapper pointMapper;

    @Autowired
    private PhmFeatureConfigMapper featureConfigMapper;

    @Autowired
    private PhmAlarmRuleMapper alarmRuleMapper;

    @Autowired
    private PhmAlarmEventMapper alarmEventMapper;

    @Autowired
    private PhmAlarmHandleRecordMapper handleRecordMapper;

    @Autowired
    private PhmDeviceEventMapper deviceEventMapper;

    @Autowired
    private PhmDeviceFavoriteMapper favoriteMapper;

    @Autowired
    private PhmAttachmentMapper attachmentMapper;

    @Autowired
    private PhmSystemConfigMapper systemConfigMapper;

    @Autowired
    private IDeviceVibrationDataService vibrationDataService;

    @Autowired
    private IDeviceTemperatureDataService temperatureDataService;

    @Override
    public Map<String, Object> getDeviceCluster(String orgName, String status, Boolean favoriteOnly, String username)
    {
        List<PhmDeviceEntity> devices = queryDevices(orgName, status);
        Set<Long> favoriteIds = favoriteIds(username);
        if (Boolean.TRUE.equals(favoriteOnly))
        {
            devices = devices.stream().filter(item -> favoriteIds.contains(item.getId())).collect(Collectors.toList());
        }

        Map<String, DeviceVibrationData> latestVibration = latestVibrationByDevice();
        Map<String, DeviceTemperatureData> latestTemperature = latestTemperatureByDevice();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PhmDeviceEntity device : devices)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", device.getId());
            row.put("deviceCode", device.getDeviceCode());
            row.put("deviceName", device.getDeviceName());
            row.put("deviceType", device.getDeviceType());
            row.put("orgName", device.getOrgName());
            row.put("location", device.getLocation());
            row.put("status", device.getStatus());
            row.put("statusText", statusText(device.getStatus()));
            row.put("healthIndex", device.getHealthIndex());
            row.put("faultType", device.getFaultType());
            row.put("runHours", device.getRunHours());
            row.put("lastAlarmTime", device.getLastAlarmTime());
            row.put("favorite", favoriteIds.contains(device.getId()));

            DeviceVibrationData vib = latestVibration.get(device.getDeviceCode());
            DeviceTemperatureData temp = latestTemperature.get(device.getDeviceCode());
            row.put("latestVibration", vib == null ? null : vib.getVibrationValue());
            row.put("latestTemperature", temp == null ? null : temp.getTemperatureValue());
            row.put("latestSampleTime", vib == null ? (temp == null ? null : temp.getCollectionTime()) : vib.getSampleTime());
            rows.add(row);
        }

        rows.sort(Comparator
                .comparing((Map<String, Object> item) -> !Boolean.TRUE.equals(item.get("favorite")))
                .thenComparing(item -> statusWeight(String.valueOf(item.get("status"))))
                .thenComparing(item -> String.valueOf(item.get("deviceCode"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("devices", rows);
        result.put("stats", buildClusterStats(devices));
        result.put("goodRateTrend", buildGoodRateTrend(devices));
        return result;
    }

    @Override
    public Map<String, Object> getDeviceBrain(Long deviceId)
    {
        PhmDeviceEntity device = deviceMapper.selectById(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", device);
        if (device == null)
        {
            result.put("points", new ArrayList<>());
            result.put("alarms", new ArrayList<>());
            result.put("events", new ArrayList<>());
            return result;
        }

        List<PhmMeasurePointEntity> points = listMeasurePoints(deviceId);
        Map<String, DeviceVibrationData> latestVibration = latestVibrationByDevice();
        Map<String, DeviceTemperatureData> latestTemperature = latestTemperatureByDevice();
        List<Map<String, Object>> pointRows = new ArrayList<>();
        for (PhmMeasurePointEntity point : points)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("point", point);
            DeviceVibrationData vib = latestVibration.get(point.getDeviceCode());
            DeviceTemperatureData temp = latestTemperature.get(point.getDeviceCode());
            row.put("latestVibration", vib == null ? null : vib.getVibrationValue());
            row.put("latestTemperature", temp == null ? null : temp.getTemperatureValue());
            row.put("trend", getFeatureTrend(point.getId(), "temperature".equals(point.getSignalType()) ? "temperature" : "vibration"));
            pointRows.add(row);
        }
        result.put("points", pointRows);
        result.put("alarms", listAlarms(device.getDeviceCode(), null, null).stream().limit(10).collect(Collectors.toList()));
        result.put("events", listDeviceEvents(deviceId, device.getDeviceCode(), null).stream().limit(10).collect(Collectors.toList()));
        result.put("latestDiagnosis", latestDiagnosis(device.getDeviceCode()));
        result.put("systemConfig", listSystemConfigs());
        return result;
    }

    @Override
    public boolean toggleFavorite(Long deviceId, String username)
    {
        LambdaQueryWrapper<PhmDeviceFavoriteEntity> wrapper = new LambdaQueryWrapper<PhmDeviceFavoriteEntity>()
                .eq(PhmDeviceFavoriteEntity::getDeviceId, deviceId)
                .eq(PhmDeviceFavoriteEntity::getUserName, username);
        PhmDeviceFavoriteEntity existed = favoriteMapper.selectOne(wrapper);
        if (existed != null)
        {
            favoriteMapper.deleteById(existed.getId());
            return false;
        }
        PhmDeviceFavoriteEntity favorite = new PhmDeviceFavoriteEntity();
        favorite.setDeviceId(deviceId);
        favorite.setUserName(username);
        favorite.setCreateTime(new Date());
        favoriteMapper.insert(favorite);
        return true;
    }

    @Override
    public List<PhmTrendPointVo> getFeatureTrend(Long pointId, String featureCode)
    {
        PhmMeasurePointEntity point = pointMapper.selectById(pointId);
        if (point == null)
        {
            return new ArrayList<>();
        }
        if ("temperature".equals(featureCode))
        {
            DeviceTemperatureData query = new DeviceTemperatureData();
            query.setDeviceCode(point.getDeviceCode());
            return temperatureDataService.selectDeviceTemperatureDataList(query).stream()
                    .limit(100)
                    .map(item -> new PhmTrendPointVo(item.getCollectionTime(), item.getTemperatureValue()))
                    .collect(Collectors.toList());
        }
        DeviceVibrationData query = new DeviceVibrationData();
        query.setDeviceCode(point.getDeviceCode());
        query.setChannelId(point.getChannelId());
        return vibrationDataService.selectDeviceVibrationDataList(query).stream()
                .limit(100)
                .map(item -> new PhmTrendPointVo(item.getSampleTime(), featureValue(item, featureCode)))
                .collect(Collectors.toList());
    }

    @Override
    public List<PhmAlarmEventEntity> listAlarms(String deviceCode, String status, Integer alarmLevel)
    {
        LambdaQueryWrapper<PhmAlarmEventEntity> wrapper = new LambdaQueryWrapper<PhmAlarmEventEntity>()
                .eq(StringUtils.hasText(deviceCode), PhmAlarmEventEntity::getDeviceCode, deviceCode)
                .eq(StringUtils.hasText(status), PhmAlarmEventEntity::getStatus, status)
                .eq(alarmLevel != null, PhmAlarmEventEntity::getAlarmLevel, alarmLevel)
                .orderByDesc(PhmAlarmEventEntity::getAlarmTime);
        return alarmEventMapper.selectList(wrapper);
    }

    @Override
    public PhmAlarmEventEntity getAlarm(Long id)
    {
        return alarmEventMapper.selectById(id);
    }

    @Override
    public boolean handleAlarm(Long id, String username, PhmAlarmActionRequest request)
    {
        return changeAlarmStatus(id, username, STATUS_HANDLED, "handle", request);
    }

    @Override
    public boolean ignoreAlarm(Long id, String username, PhmAlarmActionRequest request)
    {
        return changeAlarmStatus(id, username, STATUS_IGNORED, "ignore", request);
    }

    @Override
    public void evaluateUpload(String deviceCode, String dataType, Integer channelId, Double value, Date sampleTime)
    {
        if (!StringUtils.hasText(deviceCode) || value == null)
        {
            return;
        }
        String featureCode = "temperature".equals(dataType) ? "temperature" : "vibration";
        BigDecimal measured = BigDecimal.valueOf(value);
        List<PhmAlarmRuleEntity> rules = alarmRuleMapper.selectList(new LambdaQueryWrapper<PhmAlarmRuleEntity>()
                .eq(PhmAlarmRuleEntity::getEnabled, true)
                .eq(PhmAlarmRuleEntity::getFeatureCode, featureCode));
        if (rules.isEmpty())
        {
            return;
        }
        PhmDeviceEntity device = deviceMapper.selectOne(new LambdaQueryWrapper<PhmDeviceEntity>()
                .eq(PhmDeviceEntity::getDeviceCode, deviceCode));
        if (device == null)
        {
            return;
        }
        PhmMeasurePointEntity point = resolvePoint(deviceCode, dataType, channelId);

        PhmAlarmRuleEntity matched = null;
        String pointAlarmLevel = null;
        for (PhmAlarmRuleEntity rule : rules)
        {
            if (rule.getDeviceId() != null && !rule.getDeviceId().equals(device.getId()))
            {
                continue;
            }
            if (rule.getPointId() != null && (point == null || !rule.getPointId().equals(point.getId())))
            {
                continue;
            }
            if (rule.getHighHighLimit() != null
                    && measured.compareTo(rule.getHighHighLimit()) >= 0
                    && hasConsecutiveThreshold(point, featureCode, rule.getHighHighLimit(), rule.getConsecutiveCount()))
            {
                matched = rule;
                pointAlarmLevel = "high_high";
                break;
            }
            if (rule.getHighLimit() != null
                    && measured.compareTo(rule.getHighLimit()) >= 0
                    && hasConsecutiveThreshold(point, featureCode, rule.getHighLimit(), rule.getConsecutiveCount()))
            {
                matched = rule;
                pointAlarmLevel = "high";
                break;
            }
            BigDecimal growth = calculateGrowth(point, featureCode, measured, rule.getGrowthPeriod());
            if (growth != null && rule.getGrowthHighHighLimit() != null && growth.compareTo(rule.getGrowthHighHighLimit()) >= 0)
            {
                matched = rule;
                pointAlarmLevel = "growth_high_high";
                break;
            }
            if (growth != null && rule.getGrowthHighLimit() != null && growth.compareTo(rule.getGrowthHighLimit()) >= 0)
            {
                matched = rule;
                pointAlarmLevel = "growth_high";
                break;
            }
        }
        if (matched == null)
        {
            return;
        }
        Long openCount = alarmEventMapper.selectCount(new LambdaQueryWrapper<PhmAlarmEventEntity>()
                .eq(PhmAlarmEventEntity::getDeviceCode, deviceCode)
                .eq(PhmAlarmEventEntity::getFeatureCode, featureCode)
                .eq(PhmAlarmEventEntity::getStatus, STATUS_UNHANDLED));
        if (openCount != null && openCount > 0)
        {
            return;
        }

        PhmAlarmEventEntity event = new PhmAlarmEventEntity();
        event.setAlarmNo(buildAlarmNo("ALM", sampleTime));
        event.setDeviceId(device.getId());
        event.setDeviceCode(device.getDeviceCode());
        event.setDeviceName(device.getDeviceName());
        event.setPointId(point == null ? null : point.getId());
        event.setPointName(point == null ? null : point.getPointName());
        event.setFeatureCode(featureCode);
        event.setAlarmScope("point");
        event.setAlarmType(matched.getAlarmType());
        event.setAlarmLevel(matched.getDeviceAlarmLevel());
        event.setPointAlarmLevel(pointAlarmLevel);
        event.setAlarmValue(measured);
        event.setDiagnosisResult(matched.getActionAdvice());
        event.setStatus(STATUS_UNHANDLED);
        event.setAlarmTime(sampleTime == null ? new Date() : sampleTime);
        event.setCreateTime(new Date());
        event.setRemark("Generated by PHM rule: " + matched.getRuleName());
        alarmEventMapper.insert(event);
        SensorWebSocketHandler.broadcastPhmAlarm(event);

        device.setStatus("level" + Math.min(5, Math.max(1, matched.getDeviceAlarmLevel())));
        device.setLastAlarmTime(event.getAlarmTime());
        device.setHealthIndex(Math.max(30, Optional.ofNullable(device.getHealthIndex()).orElse(100) - 8));
        deviceMapper.updateById(device);
    }

    @Override
    public void syncDiagnosisResult(Map<String, Object> diagnosis)
    {
        if (diagnosis == null)
        {
            return;
        }
        String deviceCode = stringValue(diagnosis.get("deviceCode"), "");
        if (!StringUtils.hasText(deviceCode))
        {
            return;
        }
        PhmDeviceEntity device = deviceMapper.selectOne(new LambdaQueryWrapper<PhmDeviceEntity>()
                .eq(PhmDeviceEntity::getDeviceCode, deviceCode));
        if (device == null)
        {
            return;
        }

        Date sampleTime = diagnosis.get("sampleTime") instanceof Date ? (Date) diagnosis.get("sampleTime") : new Date();
        Integer channelId = toInteger(diagnosis.get("channelId"), 1);
        PhmMeasurePointEntity point = resolvePoint(deviceCode, "vibration", channelId);
        EnhancedInferenceRecordEntity record = persistEnhancedDiagnosis(diagnosis, sampleTime);

        String diagnosisResult = stringValue(diagnosis.get("diagnosisResult"), stringValue(diagnosis.get("diagnosisName"), "正常"));
        String diagnosisDetail = stringValue(diagnosis.get("diagnosisDetail"), "");
        String riskLevel = stringValue(diagnosis.get("riskLevel"), "低");
        String alarmLevelText = stringValue(diagnosis.get("alarmLevel"), "normal");
        int healthIndex = PhmDiagnosisLinkagePolicy.normalizeHealthIndex(
                toInteger(diagnosis.get("healthIndex"), null),
                Optional.ofNullable(device.getHealthIndex()).orElse(100));
        boolean abnormal = PhmDiagnosisLinkagePolicy.isAbnormalDiagnosis(diagnosisResult, riskLevel, alarmLevelText);

        device.setHealthIndex(healthIndex);
        device.setFaultType(abnormal ? diagnosisResult : null);
        if (abnormal)
        {
            int alarmLevel = PhmDiagnosisLinkagePolicy.diagnosisAlarmLevel(riskLevel, alarmLevelText, healthIndex);
            device.setStatus("level" + alarmLevel);
            device.setLastAlarmTime(sampleTime);
            createDiagnosisAlarm(device, point, record, diagnosisResult, diagnosisDetail, riskLevel, alarmLevel, sampleTime);
            createDiagnosisEvent(device, diagnosisResult, diagnosisDetail, riskLevel, sampleTime);
        }
        else if (!hasOpenAlarm(deviceCode))
        {
            device.setStatus("normal");
        }
        device.setUpdateTime(new Date());
        deviceMapper.updateById(device);
    }

    private EnhancedInferenceRecordEntity persistEnhancedDiagnosis(Map<String, Object> diagnosis, Date sampleTime)
    {
        EnhancedInferenceRecordEntity record = new EnhancedInferenceRecordEntity();
        record.setBatchId(toLong(diagnosis.get("batchId"), null));
        record.setDeviceCode(stringValue(diagnosis.get("deviceCode"), ""));
        record.setSourceFile(stringValue(diagnosis.get("filePath"), stringValue(diagnosis.get("filename"), null)));
        record.setAnalysisMode(stringValue(diagnosis.get("modelType"), "python"));
        record.setDiagnosisResult(stringValue(diagnosis.get("diagnosisResult"), stringValue(diagnosis.get("diagnosisName"), "正常")));
        record.setConfidence(toBigDecimal(diagnosis.get("confidence")));
        record.setHealthIndex(toInteger(diagnosis.get("healthIndex"), 100));
        record.setRiskLevel(stringValue(diagnosis.get("riskLevel"), "低"));
        record.setAlarmLevel(stringValue(diagnosis.get("alarmLevel"), "normal"));
        record.setDiagnosisDetail(stringValue(diagnosis.get("diagnosisDetail"), ""));
        record.setDecisionReason(stringValue(diagnosis.get("decisionReason"), stringValue(diagnosis.get("diagnosisDetail"), "")));
        record.setRms(toDouble(diagnosis.get("latestRms"), toDouble(diagnosis.get("rms"), 0)));
        record.setPeak(toDouble(diagnosis.get("latestPeak"), toDouble(diagnosis.get("peak"), 0)));
        record.setEvidence(JSON.toJSONString(diagnosis.getOrDefault("evidence", new ArrayList<>())));
        record.setWaveJson(JSON.toJSONString(diagnosis.getOrDefault("waveform", new ArrayList<>())));
        record.setSpectrumJson(JSON.toJSONString(diagnosis.getOrDefault("spectrum", new ArrayList<>())));
        record.setSampleTime(sampleTime);
        record.setCreateTime(new Date());
        record.setUpdateTime(new Date());
        enhancedInferenceRecordMapper.insert(record);
        return record;
    }

    private void createDiagnosisAlarm(PhmDeviceEntity device, PhmMeasurePointEntity point, EnhancedInferenceRecordEntity record,
                                      String diagnosisResult, String diagnosisDetail, String riskLevel, int alarmLevel, Date sampleTime)
    {
        Long openCount = alarmEventMapper.selectCount(new LambdaQueryWrapper<PhmAlarmEventEntity>()
                .eq(PhmAlarmEventEntity::getDeviceCode, device.getDeviceCode())
                .eq(PhmAlarmEventEntity::getAlarmType, "diagnosis")
                .eq(PhmAlarmEventEntity::getStatus, STATUS_UNHANDLED));
        if (openCount != null && openCount > 0)
        {
            return;
        }
        PhmAlarmEventEntity event = new PhmAlarmEventEntity();
        event.setAlarmNo(buildAlarmNo("DIA", sampleTime));
        event.setDeviceId(device.getId());
        event.setDeviceCode(device.getDeviceCode());
        event.setDeviceName(device.getDeviceName());
        event.setPointId(point == null ? null : point.getId());
        event.setPointName(point == null ? null : point.getPointName());
        event.setFeatureCode("diagnosis");
        event.setAlarmScope(point == null ? "device" : "point");
        event.setAlarmType("diagnosis");
        event.setAlarmLevel(alarmLevel);
        event.setPointAlarmLevel(riskLevel);
        event.setAlarmValue(BigDecimal.valueOf(Optional.ofNullable(device.getHealthIndex()).orElse(0)));
        event.setDiagnosisResult(diagnosisResult);
        event.setStatus(STATUS_UNHANDLED);
        event.setRelatedRecordId(record == null ? null : record.getId());
        event.setAlarmTime(sampleTime);
        event.setCreateTime(new Date());
        event.setRemark(StringUtils.hasText(diagnosisDetail) ? diagnosisDetail : "Generated by PHM diagnosis result");
        alarmEventMapper.insert(event);
        SensorWebSocketHandler.broadcastPhmAlarm(event);
    }

    private void createDiagnosisEvent(PhmDeviceEntity device, String diagnosisResult, String diagnosisDetail, String riskLevel, Date sampleTime)
    {
        PhmDeviceEventEntity event = new PhmDeviceEventEntity();
        event.setDeviceId(device.getId());
        event.setDeviceCode(device.getDeviceCode());
        event.setEventTime(sampleTime);
        event.setEventType(EVENT_TYPE_DIAGNOSIS);
        event.setEventContent("诊断结果：" + diagnosisResult + "，风险等级：" + riskLevel
                + (StringUtils.hasText(diagnosisDetail) ? "；" + diagnosisDetail : ""));
        event.setOperatorName("Python推理服务");
        event.setCreateTime(new Date());
        event.setUpdateTime(new Date());
        event.setRemark("PHM diagnosis linkage");
        deviceEventMapper.insert(event);
    }

    private boolean hasOpenAlarm(String deviceCode)
    {
        Long openCount = alarmEventMapper.selectCount(new LambdaQueryWrapper<PhmAlarmEventEntity>()
                .eq(PhmAlarmEventEntity::getDeviceCode, deviceCode)
                .eq(PhmAlarmEventEntity::getStatus, STATUS_UNHANDLED));
        return openCount != null && openCount > 0;
    }

    private PhmMeasurePointEntity resolvePoint(String deviceCode, String dataType, Integer channelId)
    {
        LambdaQueryWrapper<PhmMeasurePointEntity> wrapper = new LambdaQueryWrapper<PhmMeasurePointEntity>()
                .eq(PhmMeasurePointEntity::getDeviceCode, deviceCode)
                .eq(channelId != null, PhmMeasurePointEntity::getChannelId, channelId)
                .eq("temperature".equals(dataType), PhmMeasurePointEntity::getSignalType, "temperature")
                .orderByAsc(PhmMeasurePointEntity::getDisplayOrder)
                .last("limit 1");
        PhmMeasurePointEntity point = pointMapper.selectOne(wrapper);
        if (point != null || channelId == null)
        {
            return point;
        }
        return pointMapper.selectOne(new LambdaQueryWrapper<PhmMeasurePointEntity>()
                .eq(PhmMeasurePointEntity::getDeviceCode, deviceCode)
                .orderByAsc(PhmMeasurePointEntity::getDisplayOrder)
                .last("limit 1"));
    }

    private BigDecimal calculateGrowth(PhmMeasurePointEntity point, String featureCode, BigDecimal measured, Integer growthPeriod)
    {
        if (point == null || growthPeriod == null || growthPeriod <= 0 || measured == null)
        {
            return null;
        }
        List<PhmTrendPointVo> trend = getFeatureTrend(point.getId(), featureCode);
        if (trend.size() <= growthPeriod)
        {
            return null;
        }
        BigDecimal baseline = trend.get(Math.min(growthPeriod, trend.size() - 1)).getValue();
        return baseline == null ? null : measured.subtract(baseline).abs();
    }

    private boolean hasConsecutiveThreshold(PhmMeasurePointEntity point, String featureCode, BigDecimal threshold, Integer count)
    {
        int required = count == null || count <= 1 ? 1 : count;
        if (required <= 1 || point == null || threshold == null)
        {
            return true;
        }
        List<PhmTrendPointVo> trend = getFeatureTrend(point.getId(), featureCode);
        if (trend.size() < required)
        {
            return false;
        }
        for (int i = 0; i < required; i++)
        {
            BigDecimal value = trend.get(i).getValue();
            if (value == null || value.compareTo(threshold) < 0)
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<PhmAlarmRuleEntity> listAlarmRules()
    {
        return alarmRuleMapper.selectList(new LambdaQueryWrapper<PhmAlarmRuleEntity>().orderByAsc(PhmAlarmRuleEntity::getFeatureCode));
    }

    @Override
    public int saveAlarmRule(PhmAlarmRuleEntity rule)
    {
        rule.setUpdateTime(new Date());
        return rule.getId() == null ? alarmRuleMapper.insert(rule) : alarmRuleMapper.updateById(rule);
    }

    @Override
    public int removeAlarmRule(Long id)
    {
        return alarmRuleMapper.deleteById(id);
    }

    @Override
    public List<PhmDeviceEventEntity> listDeviceEvents(Long deviceId, String deviceCode, Integer year)
    {
        LambdaQueryWrapper<PhmDeviceEventEntity> wrapper = new LambdaQueryWrapper<PhmDeviceEventEntity>()
                .eq(deviceId != null, PhmDeviceEventEntity::getDeviceId, deviceId)
                .eq(StringUtils.hasText(deviceCode), PhmDeviceEventEntity::getDeviceCode, deviceCode)
                .orderByDesc(PhmDeviceEventEntity::getEventTime);
        List<PhmDeviceEventEntity> list = deviceEventMapper.selectList(wrapper);
        if (year == null)
        {
            return list;
        }
        return list.stream()
                .filter(item -> item.getEventTime() != null
                        && item.getEventTime().toInstant().atZone(ZoneId.systemDefault()).getYear() == year)
                .collect(Collectors.toList());
    }

    @Override
    public int saveDeviceEvent(PhmDeviceEventEntity event, String username)
    {
        event.setOperatorName(username);
        if (event.getCreateTime() == null)
        {
            event.setCreateTime(new Date());
        }
        event.setUpdateTime(new Date());
        return event.getId() == null ? deviceEventMapper.insert(event) : deviceEventMapper.updateById(event);
    }

    @Override
    public int removeDeviceEvent(Long id)
    {
        return deviceEventMapper.deleteById(id);
    }

    @Override
    public List<PhmDeviceEntity> listDevices(String keyword)
    {
        return deviceMapper.selectList(new LambdaQueryWrapper<PhmDeviceEntity>()
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(PhmDeviceEntity::getDeviceCode, keyword)
                        .or()
                        .like(PhmDeviceEntity::getDeviceName, keyword))
                .orderByAsc(PhmDeviceEntity::getDeviceCode));
    }

    @Override
    public int saveDevice(PhmDeviceEntity device, String username)
    {
        boolean isNew = device.getId() == null;
        if (isNew)
        {
            device.setCreateBy(username);
            device.setCreateTime(new Date());
        }
        device.setUpdateBy(username);
        device.setUpdateTime(new Date());
        int rows = isNew ? deviceMapper.insert(device) : deviceMapper.updateById(device);
        if (isNew && rows > 0)
        {
            createDeviceAccessEventIfAbsent(device, username);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeDevice(Long id)
    {
        PhmDeviceEntity device = deviceMapper.selectById(id);
        if (device == null)
        {
            return 0;
        }
        List<PhmMeasurePointEntity> points = pointMapper.selectList(new LambdaQueryWrapper<PhmMeasurePointEntity>()
                .eq(PhmMeasurePointEntity::getDeviceId, id));
        List<Long> pointIds = points.stream().map(PhmMeasurePointEntity::getId).collect(Collectors.toList());

        favoriteMapper.delete(new LambdaQueryWrapper<PhmDeviceFavoriteEntity>()
                .eq(PhmDeviceFavoriteEntity::getDeviceId, id));
        alarmRuleMapper.delete(new LambdaQueryWrapper<PhmAlarmRuleEntity>()
                .eq(PhmAlarmRuleEntity::getDeviceId, id));
        if (!pointIds.isEmpty())
        {
            alarmRuleMapper.delete(new LambdaQueryWrapper<PhmAlarmRuleEntity>()
                    .in(PhmAlarmRuleEntity::getPointId, pointIds));
        }
        deviceEventMapper.delete(new LambdaQueryWrapper<PhmDeviceEventEntity>()
                .eq(PhmDeviceEventEntity::getDeviceId, id));
        attachmentMapper.delete(new LambdaQueryWrapper<PhmAttachmentEntity>()
                .eq(PhmAttachmentEntity::getBizId, id)
                .ne(PhmAttachmentEntity::getBizType, "report"));

        LambdaQueryWrapper<PhmAlarmEventEntity> alarmWrapper = new LambdaQueryWrapper<PhmAlarmEventEntity>()
                .eq(PhmAlarmEventEntity::getDeviceId, id);
        if (StringUtils.hasText(device.getDeviceCode()))
        {
            alarmWrapper.or().eq(PhmAlarmEventEntity::getDeviceCode, device.getDeviceCode());
        }
        List<PhmAlarmEventEntity> alarms = alarmEventMapper.selectList(alarmWrapper);
        List<Long> alarmIds = alarms.stream().map(PhmAlarmEventEntity::getId).collect(Collectors.toList());
        if (!alarmIds.isEmpty())
        {
            handleRecordMapper.delete(new LambdaQueryWrapper<PhmAlarmHandleRecordEntity>()
                    .in(PhmAlarmHandleRecordEntity::getAlarmId, alarmIds));
            alarmEventMapper.deleteBatchIds(alarmIds);
        }
        pointMapper.delete(new LambdaQueryWrapper<PhmMeasurePointEntity>()
                .eq(PhmMeasurePointEntity::getDeviceId, id));
        return deviceMapper.deleteById(id);
    }

    private void createDeviceAccessEventIfAbsent(PhmDeviceEntity device, String username)
    {
        if (device == null || device.getId() == null || !StringUtils.hasText(device.getDeviceCode()))
        {
            return;
        }
        Long existed = deviceEventMapper.selectCount(new LambdaQueryWrapper<PhmDeviceEventEntity>()
                .eq(PhmDeviceEventEntity::getDeviceCode, device.getDeviceCode())
                .eq(PhmDeviceEventEntity::getEventType, PhmDeviceEventPolicy.EVENT_TYPE_ACCESS));
        if (existed != null && existed > 0)
        {
            return;
        }
        PhmDeviceEventEntity event = new PhmDeviceEventEntity();
        event.setDeviceId(device.getId());
        event.setDeviceCode(device.getDeviceCode());
        event.setEventTime(new Date());
        event.setEventType(PhmDeviceEventPolicy.EVENT_TYPE_ACCESS);
        event.setEventContent(PhmDeviceEventPolicy.buildAccessContent(device));
        event.setOperatorName(username);
        event.setCreateTime(new Date());
        event.setUpdateTime(new Date());
        event.setRemark("Auto generated when PHM device is created");
        deviceEventMapper.insert(event);
    }

    @Override
    public List<PhmMeasurePointEntity> listMeasurePoints(Long deviceId)
    {
        return pointMapper.selectList(new LambdaQueryWrapper<PhmMeasurePointEntity>()
                .eq(deviceId != null, PhmMeasurePointEntity::getDeviceId, deviceId)
                .orderByAsc(PhmMeasurePointEntity::getDisplayOrder));
    }

    @Override
    public int saveMeasurePoint(PhmMeasurePointEntity point)
    {
        point.setUpdateTime(new Date());
        return point.getId() == null ? pointMapper.insert(point) : pointMapper.updateById(point);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeMeasurePoint(Long id)
    {
        alarmRuleMapper.delete(new LambdaQueryWrapper<PhmAlarmRuleEntity>()
                .eq(PhmAlarmRuleEntity::getPointId, id));
        return pointMapper.deleteById(id);
    }

    @Override
    public List<PhmFeatureConfigEntity> listFeatureConfigs()
    {
        return featureConfigMapper.selectList(new LambdaQueryWrapper<PhmFeatureConfigEntity>()
                .orderByAsc(PhmFeatureConfigEntity::getDisplayOrder));
    }

    @Override
    public int saveFeatureConfig(PhmFeatureConfigEntity config)
    {
        if (config.getId() == null)
        {
            config.setCreateTime(new Date());
        }
        config.setUpdateTime(new Date());
        return config.getId() == null ? featureConfigMapper.insert(config) : featureConfigMapper.updateById(config);
    }

    @Override
    public int removeFeatureConfig(Long id)
    {
        return featureConfigMapper.deleteById(id);
    }

    @Override
    public List<PhmAttachmentEntity> listAttachments(String bizType, Long bizId)
    {
        return attachmentMapper.selectList(new LambdaQueryWrapper<PhmAttachmentEntity>()
                .eq(StringUtils.hasText(bizType), PhmAttachmentEntity::getBizType, bizType)
                .eq(bizId != null, PhmAttachmentEntity::getBizId, bizId)
                .orderByDesc(PhmAttachmentEntity::getCreateTime));
    }

    @Override
    public int saveAttachment(PhmAttachmentEntity attachment, String username)
    {
        if (!StringUtils.hasText(attachment.getBizType()))
        {
            attachment.setBizType("morphology");
        }
        attachment.setUploadBy(username);
        if (attachment.getCreateTime() == null)
        {
            attachment.setCreateTime(new Date());
        }
        return attachment.getId() == null ? attachmentMapper.insert(attachment) : attachmentMapper.updateById(attachment);
    }

    @Override
    public int removeAttachment(Long id)
    {
        return attachmentMapper.deleteById(id);
    }

    @Override
    public List<PhmSystemConfigEntity> listSystemConfigs()
    {
        return systemConfigMapper.selectList(new LambdaQueryWrapper<PhmSystemConfigEntity>().orderByAsc(PhmSystemConfigEntity::getConfigKey));
    }

    @Override
    public int saveSystemConfig(PhmSystemConfigEntity config)
    {
        config.setUpdateTime(new Date());
        return config.getId() == null ? systemConfigMapper.insert(config) : systemConfigMapper.updateById(config);
    }

    @Override
    public List<Map<String, Object>> getRealtimeReport(String deviceCode)
    {
        return getRealtimeReportRows(deviceCode).stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("deviceCode", row.getDeviceCode());
            map.put("deviceName", row.getDeviceName());
            map.put("status", row.getStatus());
            map.put("healthIndex", row.getHealthIndex());
            map.put("vibration", row.getVibration());
            map.put("temperature", row.getTemperature());
            map.put("sampleTime", row.getSampleTime());
            return map;
        }).collect(Collectors.toList());
    }

    @Override
    public List<PhmRealtimeReportVo> getRealtimeReportRows(String deviceCode)
    {
        List<PhmDeviceEntity> devices = listDevices(deviceCode);
        Map<String, DeviceVibrationData> latestVibration = latestVibrationByDevice();
        Map<String, DeviceTemperatureData> latestTemperature = latestTemperatureByDevice();
        List<PhmRealtimeReportVo> rows = new ArrayList<>();
        for (PhmDeviceEntity device : devices)
        {
            DeviceVibrationData vib = latestVibration.get(device.getDeviceCode());
            DeviceTemperatureData temp = latestTemperature.get(device.getDeviceCode());
            PhmRealtimeReportVo row = new PhmRealtimeReportVo();
            row.setDeviceCode(device.getDeviceCode());
            row.setDeviceName(device.getDeviceName());
            row.setStatus(device.getStatus());
            row.setHealthIndex(device.getHealthIndex());
            row.setVibration(vib == null ? null : vib.getVibrationValue());
            row.setTemperature(temp == null ? null : temp.getTemperatureValue());
            row.setSampleTime(vib == null ? (temp == null ? null : temp.getCollectionTime()) : vib.getSampleTime());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public Map<String, Object> getHistoryReport(String orgName, String deviceCode)
    {
        List<PhmDeviceEntity> devices = queryDevices(orgName, null);
        if (StringUtils.hasText(deviceCode))
        {
            devices = devices.stream().filter(item -> deviceCode.equals(item.getDeviceCode())).collect(Collectors.toList());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", buildClusterStats(devices));
        result.put("devices", getHistoryReportRows(orgName, deviceCode).stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("orgName", row.getOrgName());
            map.put("deviceCode", row.getDeviceCode());
            map.put("deviceName", row.getDeviceName());
            map.put("deviceType", row.getDeviceType());
            map.put("status", row.getStatus());
            map.put("diagnosisResult", row.getDiagnosisResult());
            map.put("alarmCount", row.getAlarmCount());
            map.put("runHours", row.getRunHours());
            map.put("healthIndex", row.getHealthIndex());
            return map;
        }).collect(Collectors.toList()));
        return result;
    }

    @Override
    public List<PhmHistoryReportVo> getHistoryReportRows(String orgName, String deviceCode)
    {
        List<PhmDeviceEntity> devices = queryDevices(orgName, null);
        if (StringUtils.hasText(deviceCode))
        {
            devices = devices.stream().filter(item -> deviceCode.equals(item.getDeviceCode())).collect(Collectors.toList());
        }
        List<PhmHistoryReportVo> rows = new ArrayList<>();
        for (PhmDeviceEntity device : devices)
        {
            PhmHistoryReportVo row = new PhmHistoryReportVo();
            row.setOrgName(device.getOrgName());
            row.setDeviceCode(device.getDeviceCode());
            row.setDeviceName(device.getDeviceName());
            row.setDeviceType(device.getDeviceType());
            row.setStatus(statusText(device.getStatus()));
            row.setDiagnosisResult(device.getFaultType());
            row.setAlarmCount(alarmEventMapper.selectCount(new LambdaQueryWrapper<PhmAlarmEventEntity>()
                    .eq(PhmAlarmEventEntity::getDeviceCode, device.getDeviceCode())));
            row.setRunHours(device.getRunHours());
            row.setHealthIndex(device.getHealthIndex());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public List<PhmAttachmentEntity> listServiceReports(String reportType)
    {
        return attachmentMapper.selectList(new LambdaQueryWrapper<PhmAttachmentEntity>()
                .eq(PhmAttachmentEntity::getBizType, "report")
                .eq(StringUtils.hasText(reportType), PhmAttachmentEntity::getReportType, reportType)
                .orderByDesc(PhmAttachmentEntity::getCreateTime));
    }

    @Override
    public int saveServiceReport(PhmAttachmentEntity attachment, String username)
    {
        attachment.setBizType("report");
        return saveAttachment(attachment, username);
    }

    private boolean changeAlarmStatus(Long id, String username, String nextStatus, String actionType, PhmAlarmActionRequest request)
    {
        PhmAlarmEventEntity alarm = alarmEventMapper.selectById(id);
        if (alarm == null)
        {
            return false;
        }
        String before = alarm.getStatus();
        alarm.setStatus(nextStatus);
        alarm.setHandler(username);
        alarm.setHandleTime(new Date());
        alarm.setIgnoreReason(request == null ? null : request.getIgnoreReason());
        alarm.setHandleRemark(request == null ? null : request.getRemark());
        alarm.setUpdateTime(new Date());
        alarmEventMapper.updateById(alarm);

        PhmAlarmHandleRecordEntity record = new PhmAlarmHandleRecordEntity();
        record.setAlarmId(id);
        record.setActionType(actionType);
        record.setOperatorName(username);
        record.setIgnoreReason(request == null ? null : request.getIgnoreReason());
        record.setBeforeStatus(before);
        record.setAfterStatus(nextStatus);
        record.setRemark(request == null ? null : request.getRemark());
        record.setCreateTime(new Date());
        handleRecordMapper.insert(record);
        refreshDeviceStatusAfterAlarm(alarm);
        createAlarmHandleEvent(alarm, username, actionType, request);
        SensorWebSocketHandler.broadcastPhmAlarmChanged(alarm);
        return true;
    }

    private void refreshDeviceStatusAfterAlarm(PhmAlarmEventEntity handledAlarm)
    {
        if (handledAlarm == null || !StringUtils.hasText(handledAlarm.getDeviceCode()))
        {
            return;
        }
        PhmDeviceEntity device = deviceMapper.selectOne(new LambdaQueryWrapper<PhmDeviceEntity>()
                .eq(PhmDeviceEntity::getDeviceCode, handledAlarm.getDeviceCode()));
        if (device == null)
        {
            return;
        }
        List<PhmAlarmEventEntity> openAlarms = alarmEventMapper.selectList(new LambdaQueryWrapper<PhmAlarmEventEntity>()
                .eq(PhmAlarmEventEntity::getDeviceCode, handledAlarm.getDeviceCode())
                .eq(PhmAlarmEventEntity::getStatus, STATUS_UNHANDLED)
                .orderByDesc(PhmAlarmEventEntity::getAlarmLevel)
                .orderByDesc(PhmAlarmEventEntity::getAlarmTime));
        if (openAlarms.isEmpty())
        {
            device.setStatus("normal");
            device.setFaultType(null);
            device.setHealthIndex(Math.min(100, Optional.ofNullable(device.getHealthIndex()).orElse(80) + 5));
        }
        else
        {
            PhmAlarmEventEntity highest = openAlarms.get(0);
            device.setStatus("level" + Math.min(5, Math.max(1, Optional.ofNullable(highest.getAlarmLevel()).orElse(1))));
            device.setFaultType(highest.getDiagnosisResult());
            device.setLastAlarmTime(highest.getAlarmTime());
        }
        device.setUpdateTime(new Date());
        deviceMapper.updateById(device);
    }

    private void createAlarmHandleEvent(PhmAlarmEventEntity alarm, String username, String actionType, PhmAlarmActionRequest request)
    {
        if (alarm == null || alarm.getDeviceId() == null)
        {
            return;
        }
        PhmDeviceEventEntity event = new PhmDeviceEventEntity();
        event.setDeviceId(alarm.getDeviceId());
        event.setDeviceCode(alarm.getDeviceCode());
        event.setEventTime(new Date());
        event.setEventType(EVENT_TYPE_ALARM_HANDLE);
        event.setEventContent(("ignore".equals(actionType) ? "忽略告警：" : "处理告警：")
                + alarm.getAlarmNo()
                + "，" + Optional.ofNullable(alarm.getDiagnosisResult()).orElse("无诊断说明")
                + (request != null && StringUtils.hasText(request.getRemark()) ? "；备注：" + request.getRemark() : "")
                + (request != null && StringUtils.hasText(request.getIgnoreReason()) ? "；原因：" + request.getIgnoreReason() : ""));
        event.setOperatorName(username);
        event.setCreateTime(new Date());
        event.setUpdateTime(new Date());
        event.setRemark("PHM alarm closed-loop action");
        deviceEventMapper.insert(event);
    }

    private List<PhmDeviceEntity> queryDevices(String orgName, String status)
    {
        return deviceMapper.selectList(new LambdaQueryWrapper<PhmDeviceEntity>()
                .like(StringUtils.hasText(orgName), PhmDeviceEntity::getOrgName, orgName)
                .eq(StringUtils.hasText(status), PhmDeviceEntity::getStatus, status)
                .orderByAsc(PhmDeviceEntity::getDeviceCode));
    }

    private Set<Long> favoriteIds(String username)
    {
        if (!StringUtils.hasText(username))
        {
            return new HashSet<>();
        }
        return favoriteMapper.selectList(new LambdaQueryWrapper<PhmDeviceFavoriteEntity>()
                .eq(PhmDeviceFavoriteEntity::getUserName, username)).stream()
                .map(PhmDeviceFavoriteEntity::getDeviceId)
                .collect(Collectors.toSet());
    }

    private Map<String, Object> buildClusterStats(List<PhmDeviceEntity> devices)
    {
        long total = devices.size();
        long stopped = devices.stream().filter(item -> "stopped".equals(item.getStatus())).count();
        long normal = devices.stream().filter(item -> "normal".equals(item.getStatus())).count();
        long alarming = devices.stream().filter(item -> statusWeight(item.getStatus()) < 6).count();
        Map<String, Long> byStatus = devices.stream().collect(Collectors.groupingBy(PhmDeviceEntity::getStatus, Collectors.counting()));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("running", Math.max(0, total - stopped));
        stats.put("stopped", stopped);
        stats.put("normal", normal);
        stats.put("alarming", alarming);
        stats.put("runningRate", total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(total - stopped).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
        stats.put("statusCount", byStatus);
        return stats;
    }

    private List<Map<String, Object>> buildGoodRateTrend(List<PhmDeviceEntity> devices)
    {
        List<Map<String, Object>> trend = new ArrayList<>();
        int total = devices.size();
        List<String> deviceCodes = devices.stream()
                .map(PhmDeviceEntity::getDeviceCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        YearMonth currentMonth = YearMonth.now();
        for (int i = 5; i >= 0; i--)
        {
            YearMonth month = currentMonth.minusMonths(i);
            Date start = Date.from(month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date end = Date.from(month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
            Set<String> alarmDeviceCodes = new HashSet<>();
            if (!deviceCodes.isEmpty())
            {
                alarmDeviceCodes = alarmEventMapper.selectList(new LambdaQueryWrapper<PhmAlarmEventEntity>()
                        .in(PhmAlarmEventEntity::getDeviceCode, deviceCodes)
                        .ge(PhmAlarmEventEntity::getAlarmTime, start)
                        .lt(PhmAlarmEventEntity::getAlarmTime, end))
                        .stream()
                        .map(PhmAlarmEventEntity::getDeviceCode)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.toSet());
            }
            int good = Math.max(0, total - alarmDeviceCodes.size());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("monthOffset", i);
            item.put("month", month.toString());
            item.put("monthLabel", month.getMonthValue() + "月");
            item.put("total", total);
            item.put("goodDeviceCount", good);
            item.put("alarmDeviceCount", alarmDeviceCodes.size());
            item.put("goodRate", total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(good).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
            trend.add(item);
        }
        return trend;
    }

    private Map<String, DeviceVibrationData> latestVibrationByDevice()
    {
        Map<String, DeviceVibrationData> map = new HashMap<>();
        for (DeviceVibrationData item : vibrationDataService.selectRecentDeviceVibrationDataList())
        {
            map.putIfAbsent(item.getDeviceCode(), item);
        }
        return map;
    }

    private Map<String, DeviceTemperatureData> latestTemperatureByDevice()
    {
        Map<String, DeviceTemperatureData> map = new HashMap<>();
        for (DeviceTemperatureData item : temperatureDataService.selectRecentDeviceTemperatureDataList())
        {
            map.putIfAbsent(item.getDeviceCode(), item);
        }
        return map;
    }

    private BigDecimal featureValue(DeviceVibrationData item, String featureCode)
    {
        if ("acceleration".equals(featureCode))
        {
            return item.getAccelerationValue();
        }
        return item.getVibrationValue();
    }

    private EnhancedInferenceRecordEntity latestDiagnosis(String deviceCode)
    {
        if (!StringUtils.hasText(deviceCode))
        {
            return null;
        }
        return enhancedInferenceRecordMapper.selectOne(new LambdaQueryWrapper<EnhancedInferenceRecordEntity>()
                .eq(EnhancedInferenceRecordEntity::getDeviceCode, deviceCode)
                .orderByDesc(EnhancedInferenceRecordEntity::getCreateTime)
                .last("limit 1"));
    }

    private String buildAlarmNo(String prefix, Date time)
    {
        Date source = time == null ? new Date() : time;
        return prefix + new SimpleDateFormat("yyyyMMddHHmmssSSS").format(source)
                + String.format("%04d", Math.abs(System.nanoTime() % 10000));
    }

    private String stringValue(Object value, String defaultValue)
    {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private Long toLong(Object value, Long defaultValue)
    {
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        if (value == null)
        {
            return defaultValue;
        }
        try
        {
            return Long.parseLong(String.valueOf(value));
        }
        catch (Exception ignored)
        {
            return defaultValue;
        }
    }

    private Integer toInteger(Object value, Integer defaultValue)
    {
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        if (value == null)
        {
            return defaultValue;
        }
        try
        {
            return Integer.parseInt(String.valueOf(value));
        }
        catch (Exception ignored)
        {
            return defaultValue;
        }
    }

    private Double toDouble(Object value, double defaultValue)
    {
        if (value instanceof Number)
        {
            return ((Number) value).doubleValue();
        }
        if (value == null)
        {
            return defaultValue;
        }
        try
        {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (Exception ignored)
        {
            return defaultValue;
        }
    }

    private BigDecimal toBigDecimal(Object value)
    {
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        if (value instanceof Number)
        {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value == null)
        {
            return null;
        }
        try
        {
            return new BigDecimal(String.valueOf(value));
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private int statusWeight(String status)
    {
        if ("level5".equals(status)) return 1;
        if ("level4".equals(status)) return 2;
        if ("level3".equals(status)) return 3;
        if ("level2".equals(status)) return 4;
        if ("level1".equals(status)) return 5;
        if ("stopped".equals(status)) return 6;
        return 7;
    }

    private String statusText(String status)
    {
        if ("level5".equals(status)) return "5级告警";
        if ("level4".equals(status)) return "4级告警";
        if ("level3".equals(status)) return "3级告警";
        if ("level2".equals(status)) return "2级告警";
        if ("level1".equals(status)) return "1级告警";
        if ("stopped".equals(status)) return "停机";
        return "正常";
    }
}
