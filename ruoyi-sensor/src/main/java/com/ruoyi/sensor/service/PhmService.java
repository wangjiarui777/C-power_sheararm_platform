package com.ruoyi.sensor.service;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import com.ruoyi.sensor.domain.dto.PhmAlarmActionRequest;
import com.ruoyi.sensor.domain.entity.PhmAlarmEventEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmHandleRecordEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmRuleEntity;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEventEntity;
import com.ruoyi.sensor.domain.entity.PhmFeatureConfigEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.entity.PhmSystemConfigEntity;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import com.ruoyi.sensor.domain.vo.PhmHistoryReportVo;
import com.ruoyi.sensor.domain.vo.PhmRealtimeReportVo;
import com.ruoyi.sensor.domain.vo.PhmTrendPointVo;

public interface PhmService
{
    Map<String, Object> getDeviceCluster(String orgName, String status, Boolean favoriteOnly, String username);

    Map<String, Object> getDeviceBrain(Long deviceId);

    boolean toggleFavorite(Long deviceId, String username);

    List<PhmTrendPointVo> getFeatureTrend(Long pointId, String featureCode);

    List<PhmAlarmEventEntity> listAlarms(String deviceCode, String status, Integer alarmLevel);

    List<PhmAlarmEventEntity> listAlarms(String deviceCode, String status, Integer alarmLevel, String alarmSource);

    Map<String, Object> getAlarm(Long id);

    boolean handleAlarm(Long id, String username, PhmAlarmActionRequest request);

    boolean ignoreAlarm(Long id, String username, PhmAlarmActionRequest request);

    boolean acknowledgeAlarm(Long id, String username, PhmAlarmActionRequest request);

    boolean assignAlarm(Long id, String username, PhmAlarmActionRequest request);

    boolean closeAlarm(Long id, String username, PhmAlarmActionRequest request);

    List<PhmAlarmHandleRecordEntity> getAlarmTimeline(Long id);

    void evaluateUpload(String deviceCode, String dataType, Integer channelId, Double value, java.util.Date sampleTime);

    void syncDiagnosisResult(Map<String, Object> diagnosis);

    void recalculateDiagnosisState(String deviceCode);

    EnhancedInferenceRecordEntity getLatestDiagnosis(String deviceCode);

    List<EnhancedInferenceRecordEntity> listLatestDiagnosesByPointIds(Collection<Long> pointIds);

    List<EnhancedInferenceRecordEntity> listDiagnosisHistory(DateRange range, String deviceCode);

    List<EnhancedInferenceRecordEntity> listDiagnosisHistory(DateRange range, String deviceCode, Long pointId);

    record DateRange(java.util.Date startTime, java.util.Date endTime) {}

    List<PhmAlarmRuleEntity> listAlarmRules();

    int saveAlarmRule(PhmAlarmRuleEntity rule);

    int removeAlarmRule(Long id);

    List<PhmDeviceEventEntity> listDeviceEvents(Long deviceId, String deviceCode, Integer year);

    int saveDeviceEvent(PhmDeviceEventEntity event, String username);

    int removeDeviceEvent(Long id);

    List<PhmDeviceEntity> listDevices(String keyword);

    int saveDevice(PhmDeviceEntity device, String username);

    int removeDevice(Long id);

    List<PhmMeasurePointEntity> listMeasurePoints(Long deviceId);

    int saveMeasurePoint(PhmMeasurePointEntity point);

    int removeMeasurePoint(Long id);

    List<PhmFeatureConfigEntity> listFeatureConfigs();

    int saveFeatureConfig(PhmFeatureConfigEntity config);

    int removeFeatureConfig(Long id);

    List<PhmAttachmentEntity> listAttachments(String bizType, Long bizId);

    int saveAttachment(PhmAttachmentEntity attachment, String username);

    int removeAttachment(Long id);

    List<PhmSystemConfigEntity> listSystemConfigs();

    int saveSystemConfig(PhmSystemConfigEntity config);

    List<Map<String, Object>> getRealtimeReport(String deviceCode);

    List<PhmRealtimeReportVo> getRealtimeReportRows(String deviceCode);

    Map<String, Object> getHistoryReport(String orgName, String deviceCode);

    List<PhmHistoryReportVo> getHistoryReportRows(String orgName, String deviceCode);

    List<PhmAttachmentEntity> listServiceReports(String reportType);

    int saveServiceReport(PhmAttachmentEntity attachment, String username);
}
