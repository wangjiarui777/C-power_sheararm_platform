package com.ruoyi.system.controller;

import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.system.domain.DeviceTemperatureData;
import com.ruoyi.system.service.IDeviceTemperatureDataService;

@RestController
@RequestMapping("/system/temperature")
public class DeviceTemperatureDataController extends BaseController
{
    @Autowired
    private IDeviceTemperatureDataService deviceTemperatureDataService;

    @PreAuthorize("@ss.hasPermi('system:temperature:list')")
    @GetMapping("/list")
    public TableDataInfo list(DeviceTemperatureData deviceTemperatureData)
    {
        startPage();
        List<DeviceTemperatureData> list = deviceTemperatureDataService.selectDeviceTemperatureDataList(deviceTemperatureData);
        return getDataTable(list);
    }

    @Anonymous
    @GetMapping("/recent")
    public AjaxResult recent()
    {
        return success(deviceTemperatureDataService.selectRecentDeviceTemperatureDataList());
    }

    @Anonymous
    @Log(title = "temperature data", businessType = BusinessType.INSERT)
    @PostMapping("/upload")
    public AjaxResult upload(@RequestBody DeviceTemperatureData deviceTemperatureData)
    {
        deviceTemperatureData.setCreateBy("collector");
        return toAjax(deviceTemperatureDataService.insertDeviceTemperatureData(deviceTemperatureData));
    }

    @PreAuthorize("@ss.hasPermi('system:temperature:export')")
    @Log(title = "temperature data", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DeviceTemperatureData deviceTemperatureData)
    {
        List<DeviceTemperatureData> list = deviceTemperatureDataService.selectDeviceTemperatureDataList(deviceTemperatureData);
        ExcelUtil<DeviceTemperatureData> util = new ExcelUtil<DeviceTemperatureData>(DeviceTemperatureData.class);
        util.exportExcel(response, list, "temperature data");
    }

    @PreAuthorize("@ss.hasPermi('system:temperature:query')")
    @GetMapping(value = "/{dataId}")
    public AjaxResult getInfo(@PathVariable("dataId") Long dataId)
    {
        return success(deviceTemperatureDataService.selectDeviceTemperatureDataById(dataId));
    }

    @PreAuthorize("@ss.hasPermi('system:temperature:add')")
    @Log(title = "temperature data", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody DeviceTemperatureData deviceTemperatureData)
    {
        deviceTemperatureData.setCreateBy(getUsername());
        return toAjax(deviceTemperatureDataService.insertDeviceTemperatureData(deviceTemperatureData));
    }

    @PreAuthorize("@ss.hasPermi('system:temperature:edit')")
    @Log(title = "temperature data", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody DeviceTemperatureData deviceTemperatureData)
    {
        deviceTemperatureData.setUpdateBy(getUsername());
        return toAjax(deviceTemperatureDataService.updateDeviceTemperatureData(deviceTemperatureData));
    }

    @PreAuthorize("@ss.hasPermi('system:temperature:remove')")
    @Log(title = "temperature data", businessType = BusinessType.DELETE)
    @DeleteMapping("/{dataIds}")
    public AjaxResult remove(@PathVariable Long[] dataIds)
    {
        return toAjax(deviceTemperatureDataService.deleteDeviceTemperatureDataByIds(dataIds));
    }
}
