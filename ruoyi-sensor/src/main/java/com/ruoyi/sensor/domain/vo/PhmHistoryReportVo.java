package com.ruoyi.sensor.domain.vo;

import java.math.BigDecimal;
import com.ruoyi.common.annotation.Excel;
import lombok.Data;

@Data
public class PhmHistoryReportVo
{
    @Excel(name = "节点")
    private String orgName;

    @Excel(name = "设备编码")
    private String deviceCode;

    @Excel(name = "设备名称")
    private String deviceName;

    @Excel(name = "设备类型")
    private String deviceType;

    @Excel(name = "运行状态")
    private String status;

    @Excel(name = "诊断结论")
    private String diagnosisResult;

    @Excel(name = "报警次数")
    private Long alarmCount;

    @Excel(name = "运行小时")
    private BigDecimal runHours;

    @Excel(name = "健康指数")
    private Integer healthIndex;
}
