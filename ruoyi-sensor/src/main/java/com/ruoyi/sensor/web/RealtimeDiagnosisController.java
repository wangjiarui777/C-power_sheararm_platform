package com.ruoyi.sensor.web;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.sensor.domain.entity.RealtimeDiagnosisPolicyEntity;
import com.ruoyi.sensor.service.RealtimeDiagnosisPolicyService;
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

@RestController
@RequestMapping("/sensor/diagnosis/realtime")
public class RealtimeDiagnosisController extends BaseController
{
    private final RealtimeDiagnosisPolicyService service;

    public RealtimeDiagnosisController(RealtimeDiagnosisPolicyService service)
    {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:realtime:list')")
    @GetMapping("/policies")
    public TableDataInfo list(@RequestParam(required = false) Long deviceId,
        @RequestParam(required = false) Long pointId, @RequestParam(required = false) String modelType)
    {
        startPage();
        return getDataTable(service.list(deviceId, pointId, modelType));
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:realtime:list')")
    @GetMapping("/status")
    public AjaxResult status()
    {
        return success(service.status());
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:realtime:edit')")
    @Log(title = "实时诊断策略", businessType = BusinessType.INSERT)
    @PostMapping("/policies")
    public AjaxResult add(@RequestBody RealtimeDiagnosisPolicyEntity policy)
    {
        return toAjax(service.save(policy));
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:realtime:edit')")
    @Log(title = "实时诊断策略", businessType = BusinessType.UPDATE)
    @PutMapping("/policies")
    public AjaxResult edit(@RequestBody RealtimeDiagnosisPolicyEntity policy)
    {
        return toAjax(service.save(policy));
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:realtime:edit')")
    @Log(title = "实时诊断策略", businessType = BusinessType.DELETE)
    @DeleteMapping("/policies/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        int count = 0;
        if (ids != null) for (Long id : ids) count += service.remove(id);
        return toAjax(count);
    }
}
