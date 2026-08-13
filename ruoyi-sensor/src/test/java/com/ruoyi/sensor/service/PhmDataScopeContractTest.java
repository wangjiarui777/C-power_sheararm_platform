package com.ruoyi.sensor.service;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhmDataScopeContractTest
{
    @Test
    void deviceQueriesUseRuoYiDepartmentDataScope() throws Exception
    {
        Method method = PhmDataScopeService.class.getMethod("listDevices", PhmDeviceScopeQuery.class);
        DataScope annotation = method.getAnnotation(DataScope.class);

        assertNotNull(annotation);
        assertEquals("d", annotation.deptAlias());
        String xml;
        try (java.io.InputStream input = getClass().getClassLoader()
            .getResourceAsStream("mapper/sensor/PhmDeviceMapper.xml"))
        {
            assertNotNull(input);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("${params.dataScope}"));
        assertTrue(xml.contains("FROM phm_device d"));
        assertTrue(xml.contains("LEFT JOIN sys_dept sd ON sd.dept_id = d.dept_id"));
        assertTrue(xml.contains("sd.dept_name AS dept_name"));
    }
}
