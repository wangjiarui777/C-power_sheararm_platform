package com.ruoyi.sensor.web;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.sensor.service.IndustrialMonitoringService;
import com.ruoyi.sensor.service.timeseries.TimeSeriesStore;

@RestController
@RequestMapping({"/sensor/monitoring", "/monitoring"})
@PreAuthorize("@ss.hasPermi('sensor:monitoring:view')")
public class IndustrialMonitoringController extends BaseController
{
    @Autowired
    private IndustrialMonitoringService monitoringService;

    @Autowired
    private TimeSeriesStore timeSeriesStore;

    @GetMapping("/timeseries/health")
    public AjaxResult timeSeriesHealth()
    {
        return success(timeSeriesStore.getStatus());
    }

    @GetMapping("/assets/tree")
    public AjaxResult assetTree()
    {
        return success(monitoringService.assetTree());
    }

    @GetMapping("/workbench")
    public AjaxResult workbench(
            @RequestParam(required = false) String deviceCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date to)
    {
        return success(monitoringService.workbench(deviceCode, from, to));
    }

    @GetMapping("/points/{pointId}/trend")
    public AjaxResult trend(
            @PathVariable Long pointId,
            @RequestParam(defaultValue = "vibration,temperature") String metrics,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date to,
            @RequestParam(defaultValue = "1200") Integer maxPoints)
    {
        Set<String> metricSet = new LinkedHashSet<>(Arrays.asList(metrics.split(",")));
        return success(monitoringService.pointTrend(pointId, metricSet, from, to, maxPoints));
    }

    @GetMapping("/points/{pointId}/vibration-analysis")
    public AjaxResult vibrationAnalysis(
            @PathVariable Long pointId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date to,
            @RequestParam(defaultValue = "1200") Integer maxPoints,
            @RequestParam(required = false) Long attachmentId)
    {
        return success(monitoringService.vibrationAnalysis(pointId, from, to, maxPoints, attachmentId));
    }

    @GetMapping("/points/{pointId}/temperature-analysis")
    public AjaxResult temperatureAnalysis(
            @PathVariable Long pointId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date to,
            @RequestParam(defaultValue = "1200") Integer maxPoints)
    {
        return success(monitoringService.temperatureAnalysis(pointId, from, to, maxPoints));
    }
}
