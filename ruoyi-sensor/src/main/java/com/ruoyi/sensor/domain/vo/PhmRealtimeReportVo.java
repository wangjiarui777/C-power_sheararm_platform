package com.ruoyi.sensor.domain.vo;

import java.math.BigDecimal;
import java.util.Date;
import com.ruoyi.common.annotation.Excel;
import lombok.Data;

@Data
public class PhmRealtimeReportVo
{
    @Excel(name = "设备编码")
    private String deviceCode;

    @Excel(name = "设备名称")
    private String deviceName;

    @Excel(name = "运行状态")
    private String status;

    @Excel(name = "健康指数")
    private Integer healthIndex;

    @Excel(name = "振动值")
    private BigDecimal vibration;

    @Excel(name = "温度值")
    private BigDecimal temperature;

    @Excel(name = "采集时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date sampleTime;
}
