package com.ruoyi.system.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.IMonitoringService;

@RestController
@RequestMapping("/system/monitoring")
public class MonitoringController extends BaseController
{
    @Autowired
    private IMonitoringService monitoringService;

    @Anonymous
    @GetMapping("/overview")
    public AjaxResult overview()
    {
        return success(monitoringService.getOverview());
    }
}
