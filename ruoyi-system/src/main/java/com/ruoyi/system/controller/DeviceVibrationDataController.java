package com.ruoyi.system.controller;

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
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.system.domain.DeviceVibrationData;
import com.ruoyi.system.event.DataUploadEvent;
import com.ruoyi.system.service.IDeviceVibrationDataService;

@RestController
@RequestMapping("/system/vibration")
public class DeviceVibrationDataController extends BaseController
{
    @Autowired
    private IDeviceVibrationDataService deviceVibrationDataService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @PreAuthorize("@ss.hasPermi('system:vibration:list')")
    @GetMapping("/list")
    public TableDataInfo list(DeviceVibrationData deviceVibrationData)
    {
        startPage();
        List<DeviceVibrationData> list = deviceVibrationDataService.selectDeviceVibrationDataList(deviceVibrationData);
        return getDataTable(list);
    }

    @Anonymous
    @GetMapping("/recent")
    public AjaxResult recent()
    {
        return success(deviceVibrationDataService.selectRecentDeviceVibrationDataList());
    }

    @Anonymous
    @Log(title = "vibration data", businessType = BusinessType.INSERT)
    @PostMapping("/upload")
    public AjaxResult upload(@RequestBody DeviceVibrationData deviceVibrationData)
    {
        deviceVibrationData.setCreateBy("collector");
        int result = deviceVibrationDataService.insertDeviceVibrationData(deviceVibrationData);
        if (result > 0)
        {
            eventPublisher.publishEvent(new DataUploadEvent(
                    deviceVibrationData.getDeviceCode(),
                    "vibration",
                    deviceVibrationData.getVibrationValue() != null
                            ? deviceVibrationData.getVibrationValue().doubleValue()
                            : null,
                    deviceVibrationData.getSampleTime()));
        }
        return toAjax(result);
    }

    @Anonymous
    @Log(title = "vibration data", businessType = BusinessType.INSERT)
    @PostMapping("/batchUpload")
    public AjaxResult batchUpload(@RequestBody List<DeviceVibrationData> deviceVibrationDataList)
    {
        if (deviceVibrationDataList == null || deviceVibrationDataList.isEmpty())
        {
            return AjaxResult.error("batch data is empty");
        }
        String username = "collector";
        for (DeviceVibrationData item : deviceVibrationDataList)
        {
            item.setCreateBy(username);
        }
        return toAjax(deviceVibrationDataService.batchInsertDeviceVibrationData(deviceVibrationDataList));
    }

    @PreAuthorize("@ss.hasPermi('system:vibration:export')")
    @Log(title = "vibration data", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DeviceVibrationData deviceVibrationData)
    {
        List<DeviceVibrationData> list = deviceVibrationDataService.selectDeviceVibrationDataList(deviceVibrationData);
        ExcelUtil<DeviceVibrationData> util = new ExcelUtil<DeviceVibrationData>(DeviceVibrationData.class);
        util.exportExcel(response, list, "vibration data");
    }

    @PreAuthorize("@ss.hasPermi('system:vibration:query')")
    @GetMapping(value = "/{dataId}")
    public AjaxResult getInfo(@PathVariable("dataId") Long dataId)
    {
        return success(deviceVibrationDataService.selectDeviceVibrationDataById(dataId));
    }

    @PreAuthorize("@ss.hasPermi('system:vibration:add')")
    @Log(title = "vibration data", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody DeviceVibrationData deviceVibrationData)
    {
        deviceVibrationData.setCreateBy(getUsername());
        return toAjax(deviceVibrationDataService.insertDeviceVibrationData(deviceVibrationData));
    }

    @PreAuthorize("@ss.hasPermi('system:vibration:edit')")
    @Log(title = "vibration data", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody DeviceVibrationData deviceVibrationData)
    {
        deviceVibrationData.setUpdateBy(getUsername());
        return toAjax(deviceVibrationDataService.updateDeviceVibrationData(deviceVibrationData));
    }

    @PreAuthorize("@ss.hasPermi('system:vibration:remove')")
    @Log(title = "vibration data", businessType = BusinessType.DELETE)
    @DeleteMapping("/{dataIds}")
    public AjaxResult remove(@PathVariable Long[] dataIds)
    {
        return toAjax(deviceVibrationDataService.deleteDeviceVibrationDataByIds(dataIds));
    }
}
