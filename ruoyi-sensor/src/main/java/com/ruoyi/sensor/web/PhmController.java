package com.ruoyi.sensor.web;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.sensor.domain.dto.PhmAlarmActionRequest;
import com.ruoyi.sensor.domain.entity.PhmAlarmRuleEntity;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEventEntity;
import com.ruoyi.sensor.domain.entity.PhmFeatureConfigEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.entity.PhmSystemConfigEntity;
import com.ruoyi.sensor.domain.vo.PhmHistoryReportVo;
import com.ruoyi.sensor.domain.vo.PhmRealtimeReportVo;
import com.ruoyi.sensor.service.PhmService;

@RestController
@RequestMapping("/phm")
public class PhmController extends BaseController
{
    @Autowired
    private PhmService phmService;

    @GetMapping("/devices/cluster")
    public AjaxResult cluster(@RequestParam(required = false) String orgName,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) Boolean favoriteOnly)
    {
        return success(phmService.getDeviceCluster(orgName, status, favoriteOnly, getUsernameSafe()));
    }

    @GetMapping("/devices/{deviceId}/brain")
    public AjaxResult brain(@PathVariable Long deviceId)
    {
        return success(phmService.getDeviceBrain(deviceId));
    }

    @PostMapping("/devices/{deviceId}/favorite")
    public AjaxResult favorite(@PathVariable Long deviceId)
    {
        return success(phmService.toggleFavorite(deviceId, getUsernameSafe()));
    }

    @GetMapping("/devices")
    public AjaxResult devices(@RequestParam(required = false) String keyword)
    {
        return success(phmService.listDevices(keyword));
    }

    @PostMapping("/devices")
    public AjaxResult addDevice(@RequestBody PhmDeviceEntity device)
    {
        return toAjax(phmService.saveDevice(device, getUsernameSafe()));
    }

    @PutMapping("/devices")
    public AjaxResult editDevice(@RequestBody PhmDeviceEntity device)
    {
        return toAjax(phmService.saveDevice(device, getUsernameSafe()));
    }

    @DeleteMapping("/devices/{deviceId}")
    public AjaxResult removeDevice(@PathVariable Long deviceId)
    {
        return toAjax(phmService.removeDevice(deviceId));
    }

    @GetMapping("/points")
    public AjaxResult points(@RequestParam(required = false) Long deviceId)
    {
        return success(phmService.listMeasurePoints(deviceId));
    }

    @PostMapping("/points")
    public AjaxResult addPoint(@RequestBody PhmMeasurePointEntity point)
    {
        return toAjax(phmService.saveMeasurePoint(point));
    }

    @PutMapping("/points")
    public AjaxResult editPoint(@RequestBody PhmMeasurePointEntity point)
    {
        return toAjax(phmService.saveMeasurePoint(point));
    }

    @DeleteMapping("/points/{pointId}")
    public AjaxResult removePoint(@PathVariable Long pointId)
    {
        return toAjax(phmService.removeMeasurePoint(pointId));
    }

    @GetMapping("/points/{pointId}/features/trend")
    public AjaxResult featureTrend(@PathVariable Long pointId,
                                   @RequestParam(defaultValue = "vibration") String featureCode)
    {
        return success(phmService.getFeatureTrend(pointId, featureCode));
    }

    @GetMapping("/features")
    public AjaxResult features()
    {
        return success(phmService.listFeatureConfigs());
    }

    @PostMapping("/features")
    public AjaxResult addFeature(@RequestBody PhmFeatureConfigEntity config)
    {
        return toAjax(phmService.saveFeatureConfig(config));
    }

    @PutMapping("/features")
    public AjaxResult editFeature(@RequestBody PhmFeatureConfigEntity config)
    {
        return toAjax(phmService.saveFeatureConfig(config));
    }

    @DeleteMapping("/features/{featureId}")
    public AjaxResult removeFeature(@PathVariable Long featureId)
    {
        return toAjax(phmService.removeFeatureConfig(featureId));
    }

    @GetMapping("/alarms")
    public AjaxResult alarms(@RequestParam(required = false) String deviceCode,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer alarmLevel)
    {
        return success(phmService.listAlarms(deviceCode, status, alarmLevel));
    }

    @GetMapping("/alarms/{alarmId}")
    public AjaxResult alarm(@PathVariable Long alarmId)
    {
        return success(phmService.getAlarm(alarmId));
    }

    @PostMapping("/alarms/{alarmId}/handle")
    public AjaxResult handleAlarm(@PathVariable Long alarmId, @RequestBody(required = false) PhmAlarmActionRequest request)
    {
        return success(phmService.handleAlarm(alarmId, getUsernameSafe(), request));
    }

    @PostMapping("/alarms/{alarmId}/ignore")
    public AjaxResult ignoreAlarm(@PathVariable Long alarmId, @RequestBody(required = false) PhmAlarmActionRequest request)
    {
        return success(phmService.ignoreAlarm(alarmId, getUsernameSafe(), request));
    }

    @GetMapping("/alarm-rules")
    public AjaxResult alarmRules()
    {
        return success(phmService.listAlarmRules());
    }

    @PostMapping("/alarm-rules")
    public AjaxResult addAlarmRule(@RequestBody PhmAlarmRuleEntity rule)
    {
        return toAjax(phmService.saveAlarmRule(rule));
    }

    @PutMapping("/alarm-rules")
    public AjaxResult editAlarmRule(@RequestBody PhmAlarmRuleEntity rule)
    {
        return toAjax(phmService.saveAlarmRule(rule));
    }

    @DeleteMapping("/alarm-rules/{ruleId}")
    public AjaxResult removeAlarmRule(@PathVariable Long ruleId)
    {
        return toAjax(phmService.removeAlarmRule(ruleId));
    }

    @GetMapping("/device-events")
    public AjaxResult deviceEvents(@RequestParam(required = false) Long deviceId,
                                   @RequestParam(required = false) String deviceCode,
                                   @RequestParam(required = false) Integer year)
    {
        return success(phmService.listDeviceEvents(deviceId, deviceCode, year));
    }

    @PostMapping("/device-events")
    public AjaxResult addDeviceEvent(@RequestBody PhmDeviceEventEntity event)
    {
        return toAjax(phmService.saveDeviceEvent(event, getUsernameSafe()));
    }

    @PutMapping("/device-events")
    public AjaxResult editDeviceEvent(@RequestBody PhmDeviceEventEntity event)
    {
        return toAjax(phmService.saveDeviceEvent(event, getUsernameSafe()));
    }

    @DeleteMapping("/device-events/{eventId}")
    public AjaxResult removeDeviceEvent(@PathVariable Long eventId)
    {
        return toAjax(phmService.removeDeviceEvent(eventId));
    }

    @GetMapping("/reports/realtime")
    public AjaxResult realtimeReport(@RequestParam(required = false) String deviceCode)
    {
        return success(phmService.getRealtimeReport(deviceCode));
    }

    @PostMapping("/reports/realtime/export")
    public void exportRealtimeReport(HttpServletResponse response,
                                     @RequestParam(required = false) String deviceCode)
    {
        List<PhmRealtimeReportVo> list = phmService.getRealtimeReportRows(deviceCode);
        ExcelUtil<PhmRealtimeReportVo> util = new ExcelUtil<>(PhmRealtimeReportVo.class);
        util.exportExcel(response, list, "PHM实时报表");
    }

    @GetMapping("/reports/history")
    public AjaxResult historyReport(@RequestParam(required = false) String orgName,
                                    @RequestParam(required = false) String deviceCode)
    {
        return success(phmService.getHistoryReport(orgName, deviceCode));
    }

    @PostMapping("/reports/history/export")
    public void exportHistoryReport(HttpServletResponse response,
                                    @RequestParam(required = false) String orgName,
                                    @RequestParam(required = false) String deviceCode)
    {
        List<PhmHistoryReportVo> list = phmService.getHistoryReportRows(orgName, deviceCode);
        ExcelUtil<PhmHistoryReportVo> util = new ExcelUtil<>(PhmHistoryReportVo.class);
        util.exportExcel(response, list, "PHM历史报表");
    }

    @GetMapping("/reports/service")
    public AjaxResult serviceReports(@RequestParam(required = false) String reportType)
    {
        return success(phmService.listServiceReports(reportType));
    }

    @PostMapping("/reports/service")
    public AjaxResult addServiceReport(@RequestBody PhmAttachmentEntity attachment)
    {
        return toAjax(phmService.saveServiceReport(attachment, getUsernameSafe()));
    }

    @GetMapping("/attachments")
    public AjaxResult attachments(@RequestParam(required = false) String bizType,
                                  @RequestParam(required = false) Long bizId)
    {
        return success(phmService.listAttachments(bizType, bizId));
    }

    @PostMapping("/attachments")
    public AjaxResult addAttachment(@RequestBody PhmAttachmentEntity attachment)
    {
        return toAjax(phmService.saveAttachment(attachment, getUsernameSafe()));
    }

    @PutMapping("/attachments")
    public AjaxResult editAttachment(@RequestBody PhmAttachmentEntity attachment)
    {
        return toAjax(phmService.saveAttachment(attachment, getUsernameSafe()));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public AjaxResult removeAttachment(@PathVariable Long attachmentId)
    {
        return toAjax(phmService.removeAttachment(attachmentId));
    }

    @GetMapping("/system-config")
    public AjaxResult systemConfig()
    {
        return success(phmService.listSystemConfigs());
    }

    @PostMapping("/system-config")
    public AjaxResult addSystemConfig(@RequestBody PhmSystemConfigEntity config)
    {
        return toAjax(phmService.saveSystemConfig(config));
    }

    @PutMapping("/system-config")
    public AjaxResult editSystemConfig(@RequestBody PhmSystemConfigEntity config)
    {
        return toAjax(phmService.saveSystemConfig(config));
    }

    private String getUsernameSafe()
    {
        try
        {
            return getUsername();
        }
        catch (Exception ex)
        {
            return "anonymous";
        }
    }
}
