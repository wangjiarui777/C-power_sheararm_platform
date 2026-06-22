package com.ruoyi.sensor.web;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.sensor.domain.DeviceTemperatureData;
import com.ruoyi.sensor.event.DataUploadEvent;
import com.ruoyi.sensor.service.IDeviceTemperatureDataService;

@RestController
@RequestMapping({"/sensor/temperature-data", "/system/temperature"})
public class DeviceTemperatureDataController extends BaseController
{
    @Autowired
    private IDeviceTemperatureDataService deviceTemperatureDataService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @PreAuthorize("@ss.hasPermi('sensor:temperature:list')")
    @GetMapping("/list")
    public TableDataInfo list(DeviceTemperatureData deviceTemperatureData)
    {
        startPage();
        List<DeviceTemperatureData> list = deviceTemperatureDataService.selectDeviceTemperatureDataList(deviceTemperatureData);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:list')")
    @GetMapping("/recent")
    public AjaxResult recent()
    {
        return success(deviceTemperatureDataService.selectRecentDeviceTemperatureDataList());
    }

    @Log(title = "temperature data", businessType = BusinessType.INSERT)
    @PreAuthorize("hasAuthority('sensor:collector:upload')")
    @PostMapping("/upload")
    public AjaxResult upload(@RequestBody DeviceTemperatureData deviceTemperatureData)
    {
        if (deviceTemperatureData.getQuality() == null)
        {
            deviceTemperatureData.setQuality("GOOD");
        }
        if (deviceTemperatureData.getReceiveTime() == null)
        {
            deviceTemperatureData.setReceiveTime(new java.util.Date());
        }
        deviceTemperatureData.setCreateBy("collector");
        int result = deviceTemperatureDataService.insertDeviceTemperatureData(deviceTemperatureData);
        if (result > 0)
        {
            eventPublisher.publishEvent(new DataUploadEvent(
                    deviceTemperatureData.getDeviceCode(),
                    "temperature",
                    deviceTemperatureData.getChannelId(),
                    deviceTemperatureData.getTemperatureValue() != null
                            ? deviceTemperatureData.getTemperatureValue().doubleValue()
                            : null,
                    deviceTemperatureData.getCollectionTime()));
        }
        return toAjax(result);
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:export')")
    @Log(title = "temperature data", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DeviceTemperatureData deviceTemperatureData)
    {
        List<DeviceTemperatureData> list = deviceTemperatureDataService.selectDeviceTemperatureDataList(deviceTemperatureData);
        ExcelUtil<DeviceTemperatureData> util = new ExcelUtil<DeviceTemperatureData>(DeviceTemperatureData.class);
        util.exportExcel(response, list, "temperature data");
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:query')")
    @GetMapping(value = "/{dataId}")
    public AjaxResult getInfo(@PathVariable("dataId") Long dataId)
    {
        return success(deviceTemperatureDataService.selectDeviceTemperatureDataById(dataId));
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:add')")
    @Log(title = "temperature data", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody DeviceTemperatureData deviceTemperatureData)
    {
        deviceTemperatureData.setCreateBy(getUsername());
        return toAjax(deviceTemperatureDataService.insertDeviceTemperatureData(deviceTemperatureData));
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:edit')")
    @Log(title = "temperature data", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody DeviceTemperatureData deviceTemperatureData)
    {
        deviceTemperatureData.setUpdateBy(getUsername());
        return toAjax(deviceTemperatureDataService.updateDeviceTemperatureData(deviceTemperatureData));
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:remove')")
    @Log(title = "temperature data", businessType = BusinessType.DELETE)
    @DeleteMapping("/{dataIds}")
    public AjaxResult remove(@PathVariable Long[] dataIds)
    {
        return toAjax(deviceTemperatureDataService.deleteDeviceTemperatureDataByIds(dataIds));
    }
}

