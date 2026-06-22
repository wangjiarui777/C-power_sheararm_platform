package com.ruoyi.sensor.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.junit.jupiter.api.Test;

class PersistenceStackCompatibilityTest
{
    @Test
    void xmlMapperBaseMapperAndPageHelperCanCoexist() throws Exception
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeAliasRegistry().registerAlias("DeviceVibrationData", DeviceVibrationData.class);
        try (InputStream input = Resources.getResourceAsStream(
                "mapper/sensor/DeviceVibrationDataMapper.xml"))
        {
            new XMLMapperBuilder(input, configuration,
                    "mapper/sensor/DeviceVibrationDataMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        assertTrue(configuration.hasStatement(
                "com.ruoyi.sensor.mapper.DeviceVibrationDataMapper.selectDeviceVibrationDataList"));
        assertTrue(BaseMapper.class.isAssignableFrom(PhmDeviceMapper.class));

        Page<Object> page = PageHelper.startPage(2, 25);
        try
        {
            assertEquals(2, page.getPageNum());
            assertEquals(25, page.getPageSize());
        }
        finally
        {
            PageHelper.clearPage();
        }
    }
}
