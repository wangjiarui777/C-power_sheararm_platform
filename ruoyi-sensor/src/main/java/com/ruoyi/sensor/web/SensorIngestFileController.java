package com.ruoyi.sensor.web;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.sensor.domain.dto.SensorIngestAssociateRequest;
import com.ruoyi.sensor.domain.query.SensorIngestFileQuery;
import com.ruoyi.sensor.service.SensorIngestFileService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unified receive ledger for browser, directory and legacy TCP files. */
@RestController
@RequestMapping("/sensor/ingest/files")
public class SensorIngestFileController extends BaseController
{
    private final SensorIngestFileService service;

    public SensorIngestFileController(SensorIngestFileService service)
    {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('sensor:ingest:list')")
    @GetMapping("/list")
    public TableDataInfo list(SensorIngestFileQuery query)
    {
        startPage();
        return getDataTable(service.list(query));
    }

    @PreAuthorize("@ss.hasPermi('sensor:ingest:associate')")
    @Log(title = "振动文件关联测点", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/point")
    public AjaxResult associate(@PathVariable Long id,
        @RequestBody SensorIngestAssociateRequest request)
    {
        return toAjax(service.associate(id, request));
    }

    @PreAuthorize("@ss.hasPermi('sensor:ingest:retry')")
    @Log(title = "振动文件接收重试", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/retry")
    public AjaxResult retry(@PathVariable Long id)
    {
        return toAjax(service.retry(id));
    }
}
