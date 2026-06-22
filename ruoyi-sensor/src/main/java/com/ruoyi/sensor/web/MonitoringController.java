package com.ruoyi.sensor.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.sensor.service.IMonitoringService;

@RestController
@RequestMapping({"/sensor/monitoring", "/system/monitoring"})
public class MonitoringController extends BaseController
{
    @Autowired
    private IMonitoringService monitoringService;

    @PreAuthorize("@ss.hasPermi('sensor:monitoring:view')")
    @GetMapping("/overview")
    public AjaxResult overview()
    {
        return success(monitoringService.getOverview());
    }
}

