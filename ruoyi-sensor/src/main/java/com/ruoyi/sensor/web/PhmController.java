package com.ruoyi.sensor.web;

import java.util.List;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.ruoyi.sensor.service.PhmAttachmentStorageService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/phm")
public class PhmController extends BaseController
{
    @Autowired
    private PhmService phmService;

    @Autowired
    private PhmAttachmentStorageService attachmentStorageService;

    @PreAuthorize("@ss.hasPermi('phm:device:list')")
    @GetMapping("/devices/cluster")
    public AjaxResult cluster(@RequestParam(required = false) String orgName,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) Boolean favoriteOnly)
    {
        return success(phmService.getDeviceCluster(orgName, status, favoriteOnly, getUsernameSafe()));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:query')")
    @GetMapping("/devices/{deviceId}/brain")
    public AjaxResult brain(@PathVariable Long deviceId)
    {
        return success(phmService.getDeviceBrain(deviceId));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:edit')")
    @PostMapping("/devices/{deviceId}/favorite")
    public AjaxResult favorite(@PathVariable Long deviceId)
    {
        return success(phmService.toggleFavorite(deviceId, getUsernameSafe()));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:list')")
    @GetMapping("/devices")
    public AjaxResult devices(@RequestParam(required = false) String keyword)
    {
        return success(phmService.listDevices(keyword));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:add')")
    @Log(title = "PHM设备新增", businessType = BusinessType.INSERT)
    @PostMapping("/devices")
    public AjaxResult addDevice(@RequestBody PhmDeviceEntity device)
    {
        return toAjax(phmService.saveDevice(device, getUsernameSafe()));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:edit')")
    @Log(title = "PHM设备修改", businessType = BusinessType.UPDATE)
    @PutMapping("/devices")
    public AjaxResult editDevice(@RequestBody PhmDeviceEntity device)
    {
        return toAjax(phmService.saveDevice(device, getUsernameSafe()));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:remove')")
    @Log(title = "PHM设备删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/devices/{deviceId}")
    public AjaxResult removeDevice(@PathVariable Long deviceId)
    {
        return toAjax(phmService.removeDevice(deviceId));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:list')")
    @GetMapping("/points")
    public AjaxResult points(@RequestParam(required = false) Long deviceId)
    {
        return success(phmService.listMeasurePoints(deviceId));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:add')")
    @PostMapping("/points")
    public AjaxResult addPoint(@RequestBody PhmMeasurePointEntity point)
    {
        return toAjax(phmService.saveMeasurePoint(point));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:edit')")
    @PutMapping("/points")
    public AjaxResult editPoint(@RequestBody PhmMeasurePointEntity point)
    {
        return toAjax(phmService.saveMeasurePoint(point));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:remove')")
    @DeleteMapping("/points/{pointId}")
    public AjaxResult removePoint(@PathVariable Long pointId)
    {
        return toAjax(phmService.removeMeasurePoint(pointId));
    }

    @PreAuthorize("@ss.hasPermi('phm:device:query')")
    @GetMapping("/points/{pointId}/features/trend")
    public AjaxResult featureTrend(@PathVariable Long pointId,
                                   @RequestParam(defaultValue = "vibration") String featureCode)
    {
        return success(phmService.getFeatureTrend(pointId, featureCode));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:list')")
    @GetMapping("/features")
    public AjaxResult features()
    {
        return success(phmService.listFeatureConfigs());
    }

    @PreAuthorize("@ss.hasPermi('phm:config:add')")
    @PostMapping("/features")
    public AjaxResult addFeature(@RequestBody PhmFeatureConfigEntity config)
    {
        return toAjax(phmService.saveFeatureConfig(config));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:edit')")
    @PutMapping("/features")
    public AjaxResult editFeature(@RequestBody PhmFeatureConfigEntity config)
    {
        return toAjax(phmService.saveFeatureConfig(config));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:remove')")
    @DeleteMapping("/features/{featureId}")
    public AjaxResult removeFeature(@PathVariable Long featureId)
    {
        return toAjax(phmService.removeFeatureConfig(featureId));
    }

    @PreAuthorize("@ss.hasPermi('phm:alarm:list')")
    @GetMapping("/alarms")
    public AjaxResult alarms(@RequestParam(required = false) String deviceCode,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer alarmLevel)
    {
        return success(phmService.listAlarms(deviceCode, status, alarmLevel));
    }

    @PreAuthorize("@ss.hasPermi('phm:alarm:query')")
    @GetMapping("/alarms/{alarmId}")
    public AjaxResult alarm(@PathVariable Long alarmId)
    {
        return success(phmService.getAlarm(alarmId));
    }

    @PreAuthorize("@ss.hasPermi('phm:alarm:handle')")
    @PostMapping("/alarms/{alarmId}/handle")
    public AjaxResult handleAlarm(@PathVariable Long alarmId, @RequestBody(required = false) PhmAlarmActionRequest request)
    {
        return success(phmService.handleAlarm(alarmId, getUsernameSafe(), request));
    }

    @PreAuthorize("@ss.hasPermi('phm:alarm:handle')")
    @PostMapping("/alarms/{alarmId}/ignore")
    public AjaxResult ignoreAlarm(@PathVariable Long alarmId, @RequestBody(required = false) PhmAlarmActionRequest request)
    {
        return success(phmService.ignoreAlarm(alarmId, getUsernameSafe(), request));
    }

    @PreAuthorize("@ss.hasPermi('phm:alarm:handle')")
    @Log(title = "PHM告警确认", businessType = BusinessType.UPDATE)
    @PostMapping("/alarms/{alarmId}/acknowledge")
    public AjaxResult acknowledgeAlarm(@PathVariable Long alarmId, @RequestBody(required = false) PhmAlarmActionRequest request)
    {
        return success(phmService.acknowledgeAlarm(alarmId, getUsernameSafe(), request));
    }

    @PreAuthorize("@ss.hasPermi('phm:alarm:handle')")
    @Log(title = "PHM告警指派", businessType = BusinessType.UPDATE)
    @PostMapping("/alarms/{alarmId}/assign")
    public AjaxResult assignAlarm(@PathVariable Long alarmId, @RequestBody PhmAlarmActionRequest request)
    {
        return success(phmService.assignAlarm(alarmId, getUsernameSafe(), request));
    }

    @PreAuthorize("@ss.hasPermi('phm:alarm:handle')")
    @Log(title = "PHM告警关闭", businessType = BusinessType.UPDATE)
    @PostMapping("/alarms/{alarmId}/close")
    public AjaxResult closeAlarm(@PathVariable Long alarmId, @RequestBody(required = false) PhmAlarmActionRequest request)
    {
        return success(phmService.closeAlarm(alarmId, getUsernameSafe(), request));
    }

    @PreAuthorize("@ss.hasPermi('phm:alarm:query')")
    @GetMapping("/alarms/{alarmId}/timeline")
    public AjaxResult alarmTimeline(@PathVariable Long alarmId)
    {
        return success(phmService.getAlarmTimeline(alarmId));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:list')")
    @GetMapping("/alarm-rules")
    public AjaxResult alarmRules()
    {
        return success(phmService.listAlarmRules());
    }

    @PreAuthorize("@ss.hasPermi('phm:config:add')")
    @Log(title = "PHM阈值新增", businessType = BusinessType.INSERT)
    @PostMapping("/alarm-rules")
    public AjaxResult addAlarmRule(@RequestBody PhmAlarmRuleEntity rule)
    {
        return toAjax(phmService.saveAlarmRule(rule));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:edit')")
    @Log(title = "PHM阈值修改", businessType = BusinessType.UPDATE)
    @PutMapping("/alarm-rules")
    public AjaxResult editAlarmRule(@RequestBody PhmAlarmRuleEntity rule)
    {
        return toAjax(phmService.saveAlarmRule(rule));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:remove')")
    @Log(title = "PHM阈值删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/alarm-rules/{ruleId}")
    public AjaxResult removeAlarmRule(@PathVariable Long ruleId)
    {
        return toAjax(phmService.removeAlarmRule(ruleId));
    }

    @PreAuthorize("@ss.hasPermi('phm:event:list')")
    @GetMapping("/device-events")
    public AjaxResult deviceEvents(@RequestParam(required = false) Long deviceId,
                                   @RequestParam(required = false) String deviceCode,
                                   @RequestParam(required = false) Integer year)
    {
        return success(phmService.listDeviceEvents(deviceId, deviceCode, year));
    }

    @PreAuthorize("@ss.hasPermi('phm:event:add')")
    @PostMapping("/device-events")
    public AjaxResult addDeviceEvent(@RequestBody PhmDeviceEventEntity event)
    {
        return toAjax(phmService.saveDeviceEvent(event, getUsernameSafe()));
    }

    @PreAuthorize("@ss.hasPermi('phm:event:edit')")
    @PutMapping("/device-events")
    public AjaxResult editDeviceEvent(@RequestBody PhmDeviceEventEntity event)
    {
        return toAjax(phmService.saveDeviceEvent(event, getUsernameSafe()));
    }

    @PreAuthorize("@ss.hasPermi('phm:event:remove')")
    @DeleteMapping("/device-events/{eventId}")
    public AjaxResult removeDeviceEvent(@PathVariable Long eventId)
    {
        return toAjax(phmService.removeDeviceEvent(eventId));
    }

    @PreAuthorize("@ss.hasPermi('phm:report:view')")
    @GetMapping("/reports/realtime")
    public AjaxResult realtimeReport(@RequestParam(required = false) String deviceCode)
    {
        return success(phmService.getRealtimeReport(deviceCode));
    }

    @PreAuthorize("@ss.hasPermi('phm:report:export')")
    @Log(title = "PHM实时报表导出", businessType = BusinessType.EXPORT)
    @PostMapping("/reports/realtime/export")
    public void exportRealtimeReport(HttpServletResponse response,
                                     @RequestParam(required = false) String deviceCode)
    {
        List<PhmRealtimeReportVo> list = phmService.getRealtimeReportRows(deviceCode);
        ExcelUtil<PhmRealtimeReportVo> util = new ExcelUtil<>(PhmRealtimeReportVo.class);
        util.exportCsv(response, list, "PHM实时报表");
    }

    @PreAuthorize("@ss.hasPermi('phm:report:view')")
    @GetMapping("/reports/history")
    public AjaxResult historyReport(@RequestParam(required = false) String orgName,
                                    @RequestParam(required = false) String deviceCode)
    {
        return success(phmService.getHistoryReport(orgName, deviceCode));
    }

    @PreAuthorize("@ss.hasPermi('phm:report:export')")
    @Log(title = "PHM历史报表导出", businessType = BusinessType.EXPORT)
    @PostMapping("/reports/history/export")
    public void exportHistoryReport(HttpServletResponse response,
                                    @RequestParam(required = false) String orgName,
                                    @RequestParam(required = false) String deviceCode)
    {
        List<PhmHistoryReportVo> list = phmService.getHistoryReportRows(orgName, deviceCode);
        ExcelUtil<PhmHistoryReportVo> util = new ExcelUtil<>(PhmHistoryReportVo.class);
        util.exportCsv(response, list, "PHM历史报表");
    }

    @PreAuthorize("@ss.hasPermi('phm:report:view')")
    @GetMapping("/reports/service")
    public AjaxResult serviceReports(@RequestParam(required = false) String reportType)
    {
        return success(phmService.listServiceReports(reportType));
    }

    @PreAuthorize("@ss.hasPermi('phm:report:edit')")
    @PostMapping("/reports/service")
    public AjaxResult addServiceReport(@RequestBody PhmAttachmentEntity attachment)
    {
        return error("请使用 /phm/attachments/upload 上传报告文件");
    }

    @PreAuthorize("@ss.hasPermi('phm:config:list')")
    @GetMapping("/attachments")
    public AjaxResult attachments(@RequestParam(required = false) String bizType,
                                  @RequestParam(required = false) Long bizId)
    {
        return success(phmService.listAttachments(bizType, bizId));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:add')")
    @PostMapping("/attachments")
    public AjaxResult addAttachment(@RequestBody PhmAttachmentEntity attachment)
    {
        return error("请使用 /phm/attachments/upload 上传附件");
    }

    @PreAuthorize("@ss.hasAnyPermi('phm:config:add,phm:report:edit,sensor:diagnosis:run')")
    @Log(title = "PHM附件上传", businessType = BusinessType.INSERT)
    @PostMapping(value = "/attachments/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult uploadAttachment(@RequestParam("file") MultipartFile file,
        @RequestParam String purpose,
        @RequestParam(required = false) String bizType,
        @RequestParam(required = false) Long bizId,
        @RequestParam(required = false) String reportType) throws Exception
    {
        PhmAttachmentEntity entity = attachmentStorageService.store(
            file, purpose, bizType, bizId, reportType, getUsernameSafe());
        return success(java.util.Map.of(
            "attachmentId", entity.getId(),
            "fileName", entity.getFileName(),
            "sha256", entity.getSha256(),
            "scanStatus", entity.getScanStatus()));
    }

    @PreAuthorize("@ss.hasAnyPermi('phm:config:list,phm:report:view,sensor:diagnosis:view')")
    @Log(title = "PHM附件下载", businessType = BusinessType.EXPORT)
    @GetMapping("/attachments/{attachmentId}/content")
    public ResponseEntity<FileSystemResource> attachmentContent(@PathVariable Long attachmentId) throws Exception
    {
        PhmAttachmentEntity entity = attachmentStorageService.getAccessible(attachmentId);
        if (entity == null)
        {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource resource = attachmentStorageService.content(entity);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(entity.getFileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(resource.contentLength())
            .body(resource);
    }

    @PreAuthorize("@ss.hasPermi('phm:config:edit')")
    @Log(title = "PHM附件元数据修改", businessType = BusinessType.UPDATE)
    @PutMapping("/attachments")
    public AjaxResult editAttachment(@RequestBody PhmAttachmentEntity attachment)
    {
        return toAjax(phmService.saveAttachment(attachment, getUsernameSafe()));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:remove')")
    @Log(title = "PHM附件删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/attachments/{attachmentId}")
    public AjaxResult removeAttachment(@PathVariable Long attachmentId)
    {
        try
        {
            return toAjax(attachmentStorageService.delete(attachmentId));
        }
        catch (Exception ex)
        {
            return error("附件删除失败: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('phm:config:list')")
    @GetMapping("/system-config")
    public AjaxResult systemConfig()
    {
        return success(phmService.listSystemConfigs());
    }

    @PreAuthorize("@ss.hasPermi('phm:config:add')")
    @PostMapping("/system-config")
    public AjaxResult addSystemConfig(@RequestBody PhmSystemConfigEntity config)
    {
        return toAjax(phmService.saveSystemConfig(config));
    }

    @PreAuthorize("@ss.hasPermi('phm:config:edit')")
    @PutMapping("/system-config")
    public AjaxResult editSystemConfig(@RequestBody PhmSystemConfigEntity config)
    {
        return toAjax(phmService.saveSystemConfig(config));
    }

    private String getUsernameSafe()
    {
        return getUsername();
    }
}
