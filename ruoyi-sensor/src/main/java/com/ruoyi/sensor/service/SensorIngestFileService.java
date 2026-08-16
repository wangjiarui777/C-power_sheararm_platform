package com.ruoyi.sensor.service;

import java.util.List;
import java.util.Objects;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.sensor.domain.dto.SensorIngestAssociateRequest;
import com.ruoyi.sensor.domain.entity.PhmAcquisitionChannelEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.entity.SensorIngestFileEntity;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.domain.query.SensorIngestFileQuery;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import com.ruoyi.sensor.mapper.SensorIngestFileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SensorIngestFileService
{
    private final SensorIngestFileMapper mapper;
    private final PhmMeasurePointMapper pointMapper;
    private final PhmAcquisitionChannelService channelService;
    private final PhmDataScopeService dataScope;

    public SensorIngestFileService(SensorIngestFileMapper mapper,
        PhmMeasurePointMapper pointMapper, PhmAcquisitionChannelService channelService,
        PhmDataScopeService dataScope)
    {
        this.mapper = mapper;
        this.pointMapper = pointMapper;
        this.channelService = channelService;
        this.dataScope = dataScope;
    }

    /** Unmapped files are visible to every list-permission user; mapped rows remain data-scoped. */
    @DataScope(deptAlias = "d")
    public List<SensorIngestFileEntity> list(SensorIngestFileQuery query)
    {
        return mapper.selectScopedList(query == null ? new SensorIngestFileQuery() : query);
    }

    @Transactional
    public int associate(Long id, SensorIngestAssociateRequest request)
    {
        if (request == null || request.getDeviceId() == null || request.getPointId() == null
            || request.getChannelId() == null)
            throw new ServiceException("设备、测点和采集通道不能为空");
        SensorIngestFileEntity file = mapper.selectById(id);
        if (file == null) throw new ServiceException("接收文件不存在");
        if (!"UNMAPPED".equals(file.getStatus()) && !"REJECTED".equals(file.getStatus()))
            throw new ServiceException("仅未映射或校验拒绝的文件可以重新关联");

        PhmDeviceScopeQuery deviceQuery = new PhmDeviceScopeQuery();
        deviceQuery.setDeviceId(request.getDeviceId());
        PhmDeviceEntity device = dataScope.getDevice(deviceQuery);
        if (device == null) throw new ServiceException("设备不存在或无权访问");
        PhmMeasurePointEntity point = pointMapper.selectById(request.getPointId());
        if (point == null || !Objects.equals(device.getId(), point.getDeviceId()))
            throw new ServiceException("测点不存在或不属于指定设备");
        PhmAcquisitionChannelEntity channel = channelService.getScoped(request.getChannelId());
        if (channel == null || !Objects.equals(device.getId(), channel.getDeviceId())
            || !Objects.equals(point.getId(), channel.getPointId()))
            throw new ServiceException("采集通道不存在、越权或尚未绑定到指定测点");
        if (file.getSha256() != null && !file.getSha256().isBlank())
        {
            Long duplicate = mapper.selectCount(new LambdaQueryWrapper<SensorIngestFileEntity>()
                .eq(SensorIngestFileEntity::getSha256, file.getSha256())
                .eq(SensorIngestFileEntity::getPointId, point.getId())
                .ne(SensorIngestFileEntity::getId, id));
            if (duplicate != null && duplicate > 0)
                throw new ServiceException("同一文件已关联到该测点，请勿重复提交");
        }

        int updated = mapper.associate(id, device.getId(), device.getDeviceCode(), point.getId(),
            point.getPointCode(), channel.getChannelNo());
        if (updated == 0) throw new ServiceException("文件状态已变化，请刷新后重试");
        return updated;
    }

    @Transactional
    public int retry(Long id)
    {
        SensorIngestFileEntity file = mapper.selectById(id);
        if (file == null) throw new ServiceException("接收文件不存在");
        if (!"FAILED".equals(file.getStatus())) throw new ServiceException("仅失败记录可以重试");
        if (file.getDeviceId() != null)
        {
            PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
            query.setDeviceId(file.getDeviceId());
            if (dataScope.getDevice(query) == null) throw new ServiceException("无权重试该设备的接收文件");
        }
        int updated = mapper.retry(id);
        if (updated == 0) throw new ServiceException("文件状态已变化，请刷新后重试");
        return updated;
    }
}
