package com.ruoyi.sensor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.ruoyi.sensor.domain.DeviceVibrationData;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.service.timeseries.TimeSeriesAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IndustrialMonitoringServiceTest
{
    @Test
    void dashboardUsesPersistedTelemetryWithoutInventingSamples()
    {
        PhmService phmService = mock(PhmService.class);
        IDeviceVibrationDataService vibrationService = mock(IDeviceVibrationDataService.class);
        IDeviceTemperatureDataService temperatureService = mock(IDeviceTemperatureDataService.class);

        PhmDeviceEntity device = new PhmDeviceEntity();
        device.setId(1L);
        device.setDeviceCode("MOTOR-01");
        device.setDeviceName("主驱电机");

        PhmMeasurePointEntity point = new PhmMeasurePointEntity();
        point.setId(11L);
        point.setDeviceId(1L);
        point.setDeviceCode("MOTOR-01");
        point.setPointCode("DE-VIB");
        point.setPointName("驱动端振动");
        point.setSignalType("vibration");
        point.setUnit("mm/s");
        point.setEnabled(true);

        Date sampleTime = new Date();
        DeviceVibrationData telemetry = new DeviceVibrationData();
        telemetry.setDeviceCode("MOTOR-01");
        telemetry.setPointId(11L);
        telemetry.setVibrationValue(new BigDecimal("2.36"));
        telemetry.setSampleTime(sampleTime);
        telemetry.setQuality("GOOD");

        when(phmService.listDevices(null)).thenReturn(List.of(device));
        when(phmService.listDevices("MOTOR-01")).thenReturn(List.of(device));
        when(phmService.listMeasurePoints(1L)).thenReturn(List.of(point));
        when(phmService.listAlarmRules()).thenReturn(Collections.emptyList());
        when(phmService.listAlarms(nullable(String.class), isNull(), isNull())).thenReturn(Collections.emptyList());
        when(phmService.getDeviceCluster(isNull(), isNull(), any(Boolean.class), isNull()))
                .thenReturn(Map.of("goodRateTrend", Collections.emptyList()));
        when(vibrationService.selectDeviceVibrationDataList(any(DeviceVibrationData.class)))
                .thenReturn(List.of(telemetry));

        IndustrialMonitoringService service = new IndustrialMonitoringService();
        ReflectionTestUtils.setField(service, "phmService", phmService);
        ReflectionTestUtils.setField(service, "vibrationService", vibrationService);
        ReflectionTestUtils.setField(service, "temperatureService", temperatureService);
        ReflectionTestUtils.setField(service, "timeSeriesAnalysisService", mock(TimeSeriesAnalysisService.class));

        Map<String, Object> dashboard = service.workbench("MOTOR-01", null, null);

        assertThat((List<?>) dashboard.get("points")).hasSize(1);
        Map<?, ?> latest = (Map<?, ?>) ((List<?>) dashboard.get("points")).get(0);
        assertThat(latest.get("value")).isEqualTo(2.36D);
        assertThat(latest.get("sampleTime")).isEqualTo(sampleTime);
        assertThat(latest.get("quality")).isEqualTo("GOOD");
    }
}
