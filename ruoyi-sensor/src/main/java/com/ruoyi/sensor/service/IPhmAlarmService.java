package com.ruoyi.sensor.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.sensor.domain.dto.PhmAlarmActionRequest;
import com.ruoyi.sensor.domain.entity.PhmAlarmEventEntity;
import com.ruoyi.sensor.domain.entity.PhmAlarmHandleRecordEntity;

/**
 * Unified alarm application service.  PhmService keeps the same methods for
 * compatibility with existing monitoring and reporting callers, while the
 * controller uses this focused contract for alarm operations.
 */
public interface IPhmAlarmService
{
    List<PhmAlarmEventEntity> listAlarms(String deviceCode, String status, Integer alarmLevel, String alarmSource);

    Map<String, Object> getAlarm(Long id);

    boolean handleAlarm(Long id, String username, PhmAlarmActionRequest request);

    boolean ignoreAlarm(Long id, String username, PhmAlarmActionRequest request);

    boolean acknowledgeAlarm(Long id, String username, PhmAlarmActionRequest request);

    boolean assignAlarm(Long id, String username, PhmAlarmActionRequest request);

    boolean closeAlarm(Long id, String username, PhmAlarmActionRequest request);

    List<PhmAlarmHandleRecordEntity> getAlarmTimeline(Long id);
}
