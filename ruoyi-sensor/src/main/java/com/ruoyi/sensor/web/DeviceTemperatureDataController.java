package com.ruoyi.sensor.web;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.sensor.domain.DeviceTemperatureData;
import com.ruoyi.sensor.service.IDeviceTemperatureDataService;
import com.ruoyi.sensor.service.PhmService;

@RestController
@RequestMapping({"/sensor/temperature-data", "/system/temperature"})
public class DeviceTemperatureDataController extends BaseController
{
    @Autowired
    private IDeviceTemperatureDataService deviceTemperatureDataService;

    @Autowired
    private PhmService phmService;

    @PreAuthorize("@ss.hasPermi('sensor:temperature:list')")
    @GetMapping("/list")
    public TableDataInfo list(DeviceTemperatureData deviceTemperatureData)
    {
        startPage();
        List<DeviceTemperatureData> list = scopedRows(
                deviceTemperatureDataService.selectDeviceTemperatureDataList(deviceTemperatureData));
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:list')")
    @GetMapping("/recent")
    public AjaxResult recent()
    {
        return success(scopedRows(deviceTemperatureDataService.selectRecentDeviceTemperatureDataList()));
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:export')")
    @Log(title = "temperature data", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DeviceTemperatureData deviceTemperatureData)
    {
        List<DeviceTemperatureData> list = scopedRows(
                deviceTemperatureDataService.selectDeviceTemperatureDataList(deviceTemperatureData));
        ExcelUtil<DeviceTemperatureData> util = new ExcelUtil<DeviceTemperatureData>(DeviceTemperatureData.class);
        util.exportCsv(response, list, "temperature_data");
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:query')")
    @GetMapping(value = "/{dataId}")
    public AjaxResult getInfo(@PathVariable("dataId") Long dataId)
    {
        DeviceTemperatureData data = deviceTemperatureDataService.selectDeviceTemperatureDataById(dataId);
        return success(data != null && accessibleDeviceCodes().contains(data.getDeviceCode()) ? data : null);
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:add')")
    @Log(title = "temperature data", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody DeviceTemperatureData deviceTemperatureData)
    {
        if (deviceTemperatureData == null || !accessibleDeviceCodes().contains(deviceTemperatureData.getDeviceCode()))
        {
            return error("设备不存在或无权访问");
        }
        deviceTemperatureData.setCreateBy(getUsername());
        return toAjax(deviceTemperatureDataService.insertDeviceTemperatureData(deviceTemperatureData));
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:edit')")
    @Log(title = "temperature data", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody DeviceTemperatureData deviceTemperatureData)
    {
        DeviceTemperatureData existing = deviceTemperatureData == null || deviceTemperatureData.getDataId() == null
                ? null : deviceTemperatureDataService.selectDeviceTemperatureDataById(deviceTemperatureData.getDataId());
        if (deviceTemperatureData == null
                || (existing != null && !accessibleDeviceCodes().contains(existing.getDeviceCode()))
                || (deviceTemperatureData.getDeviceCode() != null
                    && !accessibleDeviceCodes().contains(deviceTemperatureData.getDeviceCode())))
        {
            return error("设备不存在或无权访问");
        }
        deviceTemperatureData.setUpdateBy(getUsername());
        return toAjax(deviceTemperatureDataService.updateDeviceTemperatureData(deviceTemperatureData));
    }

    @PreAuthorize("@ss.hasPermi('sensor:temperature:remove')")
    @Log(title = "temperature data", businessType = BusinessType.DELETE)
    @DeleteMapping("/{dataIds}")
    public AjaxResult remove(@PathVariable Long[] dataIds)
    {
        Set<String> accessibleCodes = accessibleDeviceCodes();
        if (dataIds == null)
        {
            return error("数据不能为空");
        }
        for (Long dataId : dataIds)
        {
            DeviceTemperatureData data = deviceTemperatureDataService.selectDeviceTemperatureDataById(dataId);
            if (data == null || !accessibleCodes.contains(data.getDeviceCode()))
            {
                return error("包含不存在或无权访问的数据");
            }
        }
        return toAjax(deviceTemperatureDataService.deleteDeviceTemperatureDataByIds(dataIds));
    }

    private List<DeviceTemperatureData> scopedRows(List<DeviceTemperatureData> rows)
    {
        Set<String> accessibleCodes = accessibleDeviceCodes();
        return rows == null ? List.of() : rows.stream()
                .filter(item -> item != null && accessibleCodes.contains(item.getDeviceCode()))
                .collect(Collectors.toList());
    }

    private Set<String> accessibleDeviceCodes()
    {
        return phmService.listDevices(null).stream()
                .map(item -> item.getDeviceCode())
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
    }
}

