package com.ruoyi.sensor.service.support;

import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PhmDeviceEventPolicyTest
{
    @Test
    void buildsAccessContentWithSafeFallbacks()
    {
        PhmDeviceEntity device = new PhmDeviceEntity();
        device.setDeviceName("一号主轴承试验台");
        device.setDeviceCode("DEV-001");
        device.setOrgName("实验中心/PHM实训线");

        String content = PhmDeviceEventPolicy.buildAccessContent(device);
        assertContains(content, "一号主轴承试验台", "access content should include device name");
        assertContains(content, "DEV-001", "access content should include device code");
        assertContains(content, "实验中心/PHM实训线", "access content should include organization");
        assertContains(content, "接入 PHM 平台", "access content should describe access action");

        String fallback = PhmDeviceEventPolicy.buildAccessContent(new PhmDeviceEntity());
        assertContains(fallback, "设备", "fallback content should include generic device name");
        assertContains(fallback, "--", "fallback content should include generic device code");
        assertContains(fallback, "未设置组织", "fallback content should include generic organization");
    }

    private static void assertContains(String text, String expected, String message)
    {
        assertTrue(text != null && text.contains(expected),
                message + ", expected fragment=" + expected + ", actual=" + text);
    }
}
