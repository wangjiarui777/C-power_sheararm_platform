package com.ruoyi.sensor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ruoyi.sensor.domain.dto.SensorIngestAssociateRequest;
import com.ruoyi.sensor.domain.entity.PhmAcquisitionChannelEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.entity.SensorIngestFileEntity;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import com.ruoyi.sensor.mapper.SensorIngestFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SensorIngestFileServiceTest
{
    private SensorIngestFileMapper mapper;
    private PhmMeasurePointMapper pointMapper;
    private PhmAcquisitionChannelService channelService;
    private PhmDataScopeService dataScope;
    private SensorIngestFileService service;

    @BeforeEach
    void setUp()
    {
        mapper = mock(SensorIngestFileMapper.class);
        pointMapper = mock(PhmMeasurePointMapper.class);
        channelService = mock(PhmAcquisitionChannelService.class);
        dataScope = mock(PhmDataScopeService.class);
        service = new SensorIngestFileService(mapper, pointMapper, channelService, dataScope);
    }

    @Test
    void rejectsAssociationToDeviceOutsideDataScope()
    {
        when(mapper.selectById(7L)).thenReturn(file("UNMAPPED"));

        assertThatThrownBy(() -> service.associate(7L, request()))
            .hasMessage("设备不存在或无权访问");
    }

    @Test
    void rejectsPointThatDoesNotBelongToTargetDevice()
    {
        when(mapper.selectById(7L)).thenReturn(file("UNMAPPED"));
        when(dataScope.getDevice(any())).thenReturn(device());
        PhmMeasurePointEntity foreignPoint = new PhmMeasurePointEntity();
        foreignPoint.setId(20L);
        foreignPoint.setDeviceId(2L);
        when(pointMapper.selectById(20L)).thenReturn(foreignPoint);

        assertThatThrownBy(() -> service.associate(7L, request()))
            .hasMessage("测点不存在或不属于指定设备");
    }

    @Test
    void associatesOnlyMatchingScopedChannel()
    {
        SensorIngestFileEntity file = file("UNMAPPED");
        when(mapper.selectById(7L)).thenReturn(file);
        when(dataScope.getDevice(any())).thenReturn(device());
        when(pointMapper.selectById(20L)).thenReturn(point());
        when(channelService.getScoped(30L)).thenReturn(channel());
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.associate(7L, 1L, "MOTOR-01", 20L, "DE-VIB", 3)).thenReturn(1);

        assertThat(service.associate(7L, request())).isEqualTo(1);
        verify(mapper).associate(7L, 1L, "MOTOR-01", 20L, "DE-VIB", 3);
    }

    @Test
    void rejectsDuplicateFileMappingWithBusinessMessage()
    {
        when(mapper.selectById(7L)).thenReturn(file("UNMAPPED"));
        when(dataScope.getDevice(any())).thenReturn(device());
        when(pointMapper.selectById(20L)).thenReturn(point());
        when(channelService.getScoped(30L)).thenReturn(channel());
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.associate(7L, request()))
            .hasMessage("同一文件已关联到该测点，请勿重复提交");
    }

    @Test
    void retriesFailedRowsOnly()
    {
        when(mapper.selectById(7L)).thenReturn(file("READY"));
        assertThatThrownBy(() -> service.retry(7L)).hasMessage("仅失败记录可以重试");

        when(mapper.selectById(7L)).thenReturn(file("FAILED"));
        when(mapper.retry(7L)).thenReturn(1);
        assertThat(service.retry(7L)).isEqualTo(1);
    }

    private SensorIngestAssociateRequest request()
    {
        SensorIngestAssociateRequest request = new SensorIngestAssociateRequest();
        request.setDeviceId(1L);
        request.setPointId(20L);
        request.setChannelId(30L);
        return request;
    }

    private SensorIngestFileEntity file(String status)
    {
        SensorIngestFileEntity file = new SensorIngestFileEntity();
        file.setId(7L);
        file.setStatus(status);
        file.setSha256("abc123");
        return file;
    }

    private PhmDeviceEntity device()
    {
        PhmDeviceEntity device = new PhmDeviceEntity();
        device.setId(1L);
        device.setDeviceCode("MOTOR-01");
        return device;
    }

    private PhmMeasurePointEntity point()
    {
        PhmMeasurePointEntity point = new PhmMeasurePointEntity();
        point.setId(20L);
        point.setDeviceId(1L);
        point.setPointCode("DE-VIB");
        return point;
    }

    private PhmAcquisitionChannelEntity channel()
    {
        PhmAcquisitionChannelEntity channel = new PhmAcquisitionChannelEntity();
        channel.setId(30L);
        channel.setDeviceId(1L);
        channel.setPointId(20L);
        channel.setChannelNo(3);
        return channel;
    }
}
