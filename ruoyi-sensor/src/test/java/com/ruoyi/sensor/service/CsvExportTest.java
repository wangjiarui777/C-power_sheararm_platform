package com.ruoyi.sensor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.utils.poi.ExcelUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class CsvExportTest
{
    @Test
    void exportsUtf8CsvWithAnnotationFormattingAndInjectionProtection() throws Exception
    {
        CsvRow row = new CsvRow();
        row.name = "轴承,\"A\"";
        row.status = "1";
        row.sampleTime = new Date(0);
        row.formula = "=1+1";

        MockHttpServletResponse response = new MockHttpServletResponse();
        TimeZone previous = TimeZone.getDefault();
        try
        {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"));
            new ExcelUtil<CsvRow>(CsvRow.class).exportCsv(response, List.of(row), "监测数据");
        }
        finally
        {
            TimeZone.setDefault(previous);
        }

        byte[] bytes = response.getContentAsByteArray();
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\ufeff名称,状态,采集时间,公式\r\n"));
        assertTrue(csv.contains("\"轴承,\"\"A\"\"\",正常,1970-01-01 08:00:00,\t=1+1\r\n"));
        assertEquals("text/csv;charset=UTF-8", response.getContentType());
        assertTrue(response.getHeader("Content-Disposition").contains(".csv"));
    }

    private static class CsvRow
    {
        @Excel(name = "名称", sort = 1)
        private String name;

        @Excel(name = "状态", sort = 2, readConverterExp = "0=停用,1=正常")
        private String status;

        @Excel(name = "采集时间", sort = 3, dateFormat = "yyyy-MM-dd HH:mm:ss")
        private Date sampleTime;

        @Excel(name = "公式", sort = 4)
        private String formula;
    }
}
