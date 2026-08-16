package com.ruoyi.sensor.web;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.sensor.domain.entity.PhmAcquisitionChannelEntity;
import com.ruoyi.sensor.service.PhmAcquisitionChannelService;
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
@RequestMapping("/sensor/access/channels")
public class PhmAcquisitionChannelController extends BaseController
{
    private final PhmAcquisitionChannelService service;

    public PhmAcquisitionChannelController(PhmAcquisitionChannelService service) { this.service = service; }

    @PreAuthorize("@ss.hasAnyPermi('sensor:channel:list,sensor:ingest:associate')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) Long deviceId,
        @RequestParam(required = false) Long pointId)
    {
        startPage();
        return getDataTable(service.list(deviceId, pointId));
    }

    @PreAuthorize("@ss.hasAnyPermi('sensor:channel:list,sensor:ingest:list')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(service.options());
    }

    @PreAuthorize("@ss.hasPermi('sensor:channel:add')")
    @Log(title = "采集通道", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody PhmAcquisitionChannelEntity channel)
    { return toAjax(service.save(channel)); }

    @PreAuthorize("@ss.hasPermi('sensor:channel:edit')")
    @Log(title = "采集通道", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody PhmAcquisitionChannelEntity channel)
    { return toAjax(service.save(channel)); }

    @PreAuthorize("@ss.hasPermi('sensor:channel:remove')")
    @Log(title = "采集通道", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        int count = 0;
        if (ids != null) for (Long id : ids) count += service.remove(id);
        return toAjax(count);
    }
}
