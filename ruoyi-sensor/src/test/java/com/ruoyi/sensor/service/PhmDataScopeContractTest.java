package com.ruoyi.sensor.service;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhmDataScopeContractTest
{
    @Test
    void deviceQueriesUseDirectUserDeviceAuthorization() throws Exception
    {
        String xml = resource("mapper/sensor/PhmDeviceMapper.xml");
        assertTrue(xml.contains("sys_user_device"));
        assertTrue(xml.contains("ud.user_id = #{scopeUserId}"));
        assertTrue(xml.contains("ud.device_id = d.id"));
        assertTrue(xml.contains("FROM phm_device d"));
        assertTrue(xml.contains("LEFT JOIN sys_dept sd ON sd.dept_id = d.dept_id"));
        assertTrue(xml.contains("sd.dept_name AS dept_name"));
    }

    @Test
    void ingestLedgerKeepsUnmappedRowsGlobalAndScopesMappedRowsByUser() throws Exception
    {
        String xml = resource("mapper/sensor/SensorIngestFileMapper.xml");
        assertTrue(xml.contains("f.device_id IS NULL OR"));
        assertTrue(xml.contains("ud.user_id = #{scopeUserId}"));
        assertTrue(xml.contains("ud.device_id = f.device_id"));
        assertTrue(xml.contains("LEFT JOIN phm_device d ON d.id = f.device_id"));
    }

    @Test
    void channelPagingQueryUsesTheSameUserDeviceAuthorization() throws Exception
    {
        String xml = resource("mapper/sensor/PhmAcquisitionChannelMapper.xml");
        assertTrue(xml.contains("ud.user_id = #{scopeUserId}"));
        assertTrue(xml.contains("ud.device_id = c.device_id"));
        assertTrue(xml.contains("INNER JOIN phm_device d ON d.id = c.device_id"));
    }

    private String resource(String path) throws Exception
    {
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(path))
        {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
