package com.ruoyi.sensor.domain.vo;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Sanitized choices used by the acquisition-channel and ingest workbenches. */
@Data
public class AcquisitionChannelOptionsVo
{
    private List<DeviceOption> devices = new ArrayList<>();
    private List<PointOption> points = new ArrayList<>();
    private List<CollectorOption> collectors = new ArrayList<>();

    @Data
    public static class DeviceOption
    {
        private Long id;
        private String deviceCode;
        private String deviceName;
    }

    @Data
    public static class PointOption
    {
        private Long id;
        private Long deviceId;
        private String pointCode;
        private String pointName;
        private String signalType;
        private String unit;
    }

    @Data
    public static class CollectorOption
    {
        private String collectorId;
        private String collectorName;
        private Boolean enabled;
    }
}
