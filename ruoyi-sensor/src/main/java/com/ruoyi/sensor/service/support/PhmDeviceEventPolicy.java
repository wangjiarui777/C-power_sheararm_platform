package com.ruoyi.sensor.service.support;

import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;

public final class PhmDeviceEventPolicy
{
    public static final String EVENT_TYPE_ACCESS = "access";

    private PhmDeviceEventPolicy()
    {
    }

    public static String buildAccessContent(PhmDeviceEntity device)
    {
        String deviceName = device == null || !hasText(device.getDeviceName()) ? "设备" : device.getDeviceName();
        String deviceCode = device == null || !hasText(device.getDeviceCode()) ? "--" : device.getDeviceCode();
        String orgName = device == null || !hasText(device.getOrgName()) ? "未设置组织" : device.getOrgName();
        return deviceName + "（" + deviceCode + "）接入 PHM 平台，所属节点：" + orgName + "。";
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.trim().isEmpty();
    }
}
