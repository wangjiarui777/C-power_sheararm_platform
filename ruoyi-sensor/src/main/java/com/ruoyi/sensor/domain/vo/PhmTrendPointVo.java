package com.ruoyi.sensor.domain.vo;

import java.math.BigDecimal;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PhmTrendPointVo
{
    private Date sampleTime;
    private BigDecimal value;
}
