package com.ruoyi.sensor.domain.taos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SensorFftPointEntity
{
    private String deviceCode;
    private Long ts;
    private Double frequency;
    private Double amplitude;
    private Integer pointIndex;
}
