package com.ruoyi.sensor.service;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.ruoyi.common.annotation.DataScope;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.sensor.domain.entity.CollectorCredentialEntity;
import com.ruoyi.sensor.domain.entity.PhmAcquisitionChannelEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.domain.query.PhmAcquisitionChannelQuery;
import com.ruoyi.sensor.domain.vo.AcquisitionChannelOptionsVo;
import com.ruoyi.sensor.mapper.CollectorCredentialMapper;
import com.ruoyi.sensor.mapper.PhmAcquisitionChannelMapper;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PhmAcquisitionChannelService
{
    private final PhmAcquisitionChannelMapper channelMapper;
    private final PhmMeasurePointMapper pointMapper;
    private final CollectorCredentialMapper collectorMapper;
    private final PhmDataScopeService dataScope;

    public PhmAcquisitionChannelService(PhmAcquisitionChannelMapper channelMapper,
        PhmMeasurePointMapper pointMapper, CollectorCredentialMapper collectorMapper,
        PhmDataScopeService dataScope)
    {
        this.channelMapper = channelMapper;
        this.pointMapper = pointMapper;
        this.collectorMapper = collectorMapper;
        this.dataScope = dataScope;
    }

    @DataScope(deptAlias = "d")
    public List<PhmAcquisitionChannelEntity> list(Long deviceId, Long pointId)
    {
        PhmAcquisitionChannelQuery query = new PhmAcquisitionChannelQuery();
        query.setDeviceId(deviceId);
        query.setPointId(pointId);
        return channelMapper.selectScopedList(query);
    }

    public AcquisitionChannelOptionsVo options()
    {
        AcquisitionChannelOptionsVo result = new AcquisitionChannelOptionsVo();
        List<PhmDeviceEntity> devices = allowedDevices();
        Set<Long> deviceIds = devices.stream().map(PhmDeviceEntity::getId).collect(Collectors.toSet());
        result.setDevices(devices.stream().map(device -> {
            AcquisitionChannelOptionsVo.DeviceOption option = new AcquisitionChannelOptionsVo.DeviceOption();
            option.setId(device.getId());
            option.setDeviceCode(device.getDeviceCode());
            option.setDeviceName(device.getDeviceName());
            return option;
        }).toList());
        if (!deviceIds.isEmpty())
        {
            List<PhmMeasurePointEntity> points = pointMapper.selectList(
                new LambdaQueryWrapper<PhmMeasurePointEntity>()
                    .in(PhmMeasurePointEntity::getDeviceId, deviceIds)
                    .orderByAsc(PhmMeasurePointEntity::getDeviceId)
                    .orderByAsc(PhmMeasurePointEntity::getDisplayOrder));
            result.setPoints(points.stream().map(point -> {
                AcquisitionChannelOptionsVo.PointOption option = new AcquisitionChannelOptionsVo.PointOption();
                option.setId(point.getId());
                option.setDeviceId(point.getDeviceId());
                option.setPointCode(point.getPointCode());
                option.setPointName(point.getPointName());
                option.setSignalType(point.getSignalType());
                option.setUnit(point.getUnit());
                return option;
            }).toList());
        }
        List<CollectorCredentialEntity> collectors = collectorMapper.selectList(
            new LambdaQueryWrapper<CollectorCredentialEntity>()
                .orderByDesc(CollectorCredentialEntity::getEnabled)
                .orderByAsc(CollectorCredentialEntity::getCollectorId));
        result.setCollectors(collectors.stream().map(collector -> {
            AcquisitionChannelOptionsVo.CollectorOption option = new AcquisitionChannelOptionsVo.CollectorOption();
            option.setCollectorId(collector.getCollectorId());
            option.setCollectorName(collector.getCollectorName());
            option.setEnabled(collector.getEnabled());
            return option;
        }).toList());
        return result;
    }

    @Transactional
    public int save(PhmAcquisitionChannelEntity channel)
    {
        if (channel == null || channel.getDeviceId() == null)
            throw new IllegalArgumentException("设备不能为空");
        PhmDeviceEntity device = scopedDevice(channel.getDeviceId());
        if (device == null) throw new IllegalArgumentException("设备不存在或无权访问");
        if (channel.getId() != null && getScoped(channel.getId()) == null)
            throw new ServiceException("采集通道不存在或无权修改");
        if (channel.getCollectorId() == null || !channel.getCollectorId().matches("[A-Za-z0-9_-]{3,64}"))
            throw new IllegalArgumentException("采集器编码格式错误");
        if (channel.getModuleNo() == null || channel.getModuleNo() < 1)
            throw new IllegalArgumentException("模块号必须大于零");
        if (channel.getChannelNo() == null || channel.getChannelNo() < 1 || channel.getChannelNo() > 64)
            throw new IllegalArgumentException("通道号必须在 1 到 64 之间");
        if (channel.getPointId() != null)
        {
            PhmMeasurePointEntity point = pointMapper.selectById(channel.getPointId());
            if (point == null || !channel.getDeviceId().equals(point.getDeviceId()))
                throw new IllegalArgumentException("测点不存在或不属于指定设备");
            channel.setPointCode(point.getPointCode());
            channel.setSignalType(point.getSignalType());
            channel.setDeviceCode(device.getDeviceCode());
            if (channel.getUnit() == null || channel.getUnit().isBlank()) channel.setUnit(point.getUnit());
        }
        else
        {
            channel.setDeviceCode(device.getDeviceCode());
            channel.setPointCode(null);
        }
        Long duplicate = channelMapper.selectCount(new LambdaQueryWrapper<PhmAcquisitionChannelEntity>()
            .eq(PhmAcquisitionChannelEntity::getCollectorId, channel.getCollectorId())
            .eq(PhmAcquisitionChannelEntity::getModuleNo, channel.getModuleNo())
            .eq(PhmAcquisitionChannelEntity::getChannelNo, channel.getChannelNo())
            .ne(channel.getId() != null, PhmAcquisitionChannelEntity::getId, channel.getId()));
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("采集器模块通道已存在");
        Date now = new Date();
        if (channel.getScaleFactor() == null) channel.setScaleFactor(java.math.BigDecimal.ONE);
        if (channel.getOffsetValue() == null) channel.setOffsetValue(java.math.BigDecimal.ZERO);
        if (channel.getQualityPolicySeconds() == null) channel.setQualityPolicySeconds(300);
        if (channel.getEnabled() == null) channel.setEnabled(true);
        if (channel.getId() == null) channel.setCreateTime(now);
        channel.setUpdateTime(now);
        int affected = channel.getId() == null ? channelMapper.insert(channel) : channelMapper.updateById(channel);
        if (affected > 0 && channel.getPointId() != null)
        {
            PhmMeasurePointEntity point = pointMapper.selectById(channel.getPointId());
            if (point != null && !java.util.Objects.equals(point.getChannelId(), channel.getChannelNo()))
            {
                point.setChannelId(channel.getChannelNo());
                point.setUpdateTime(now);
                pointMapper.updateById(point);
            }
        }
        return affected;
    }

    @Transactional
    public int remove(Long id)
    {
        PhmAcquisitionChannelEntity channel = channelMapper.selectById(id);
        if (channel == null || scopedDevice(channel.getDeviceId()) == null) return 0;
        return channelMapper.deleteById(id);
    }

    public PhmAcquisitionChannelEntity getScoped(Long id)
    {
        if (id == null) return null;
        PhmAcquisitionChannelEntity channel = channelMapper.selectById(id);
        return channel != null && scopedDevice(channel.getDeviceId()) != null ? channel : null;
    }

    private List<PhmDeviceEntity> allowedDevices()
    {
        return dataScope.listDevices(new PhmDeviceScopeQuery());
    }

    private PhmDeviceEntity scopedDevice(Long id)
    {
        PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
        query.setDeviceId(id);
        return dataScope.getDevice(query);
    }
}
