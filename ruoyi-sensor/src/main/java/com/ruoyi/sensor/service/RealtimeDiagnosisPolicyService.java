package com.ruoyi.sensor.service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.entity.RealtimeDiagnosisPolicyEntity;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import com.ruoyi.sensor.mapper.RealtimeDiagnosisPolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RealtimeDiagnosisPolicyService
{
    private static final Set<String> MODEL_TYPES = Set.of("gear", "bearing");
    private final RealtimeDiagnosisPolicyMapper policyMapper;
    private final PhmMeasurePointMapper pointMapper;
    private final PhmDataScopeService dataScopeService;
    private final RealtimeWindowBuffer windowBuffer;
    private final RealtimeDiagnosisDispatchService dispatchService;

    public RealtimeDiagnosisPolicyService(RealtimeDiagnosisPolicyMapper policyMapper,
        PhmMeasurePointMapper pointMapper, PhmDataScopeService dataScopeService,
        RealtimeWindowBuffer windowBuffer, RealtimeDiagnosisDispatchService dispatchService)
    {
        this.policyMapper = policyMapper;
        this.pointMapper = pointMapper;
        this.dataScopeService = dataScopeService;
        this.windowBuffer = windowBuffer;
        this.dispatchService = dispatchService;
    }

    public List<RealtimeDiagnosisPolicyEntity> list(Long deviceId, Long pointId, String modelType)
    {
        Set<Long> deviceIds = accessibleDevices().stream().map(PhmDeviceEntity::getId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (deviceIds.isEmpty()) return List.of();
        LambdaQueryWrapper<RealtimeDiagnosisPolicyEntity> query = new LambdaQueryWrapper<>();
        query.in(RealtimeDiagnosisPolicyEntity::getDeviceId, deviceIds)
            .eq(deviceId != null, RealtimeDiagnosisPolicyEntity::getDeviceId, deviceId)
            .eq(pointId != null, RealtimeDiagnosisPolicyEntity::getPointId, pointId)
            .eq(modelType != null && !modelType.isBlank(), RealtimeDiagnosisPolicyEntity::getModelType, modelType)
            .orderByDesc(RealtimeDiagnosisPolicyEntity::getUpdateTime);
        return policyMapper.selectList(query);
    }

    public RealtimeDiagnosisPolicyEntity getScoped(Long id)
    {
        if (id == null) return null;
        RealtimeDiagnosisPolicyEntity policy = policyMapper.selectById(id);
        if (policy == null || !accessibleDeviceIds().contains(policy.getDeviceId())) return null;
        return policy;
    }

    @Transactional
    public int save(RealtimeDiagnosisPolicyEntity policy)
    {
        validate(policy);
        if (policy.getId() != null && getScoped(policy.getId()) == null)
            throw new ServiceException("实时诊断策略不存在或无权修改");
        PhmMeasurePointEntity point = pointMapper.selectById(policy.getPointId());
        if (point == null || !policy.getDeviceId().equals(point.getDeviceId()))
            throw new IllegalArgumentException("测点不存在或不属于指定设备");
        if (!accessibleDeviceIds().contains(policy.getDeviceId()))
            throw new IllegalArgumentException("设备不存在或无权访问");
        Long duplicate = policyMapper.selectCount(new LambdaQueryWrapper<RealtimeDiagnosisPolicyEntity>()
            .eq(RealtimeDiagnosisPolicyEntity::getPointId, policy.getPointId())
            .eq(RealtimeDiagnosisPolicyEntity::getModelType, policy.getModelType())
            .ne(policy.getId() != null, RealtimeDiagnosisPolicyEntity::getId, policy.getId()));
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("同一测点的模型策略已存在");
        Date now = new Date();
        if (policy.getWindowSamples() == null) policy.setWindowSamples(5120);
        if (policy.getStrideSamples() == null) policy.setStrideSamples(policy.getWindowSamples());
        if (policy.getMinIntervalSeconds() == null) policy.setMinIntervalSeconds(30);
        if (policy.getAlarmCooldownSeconds() == null) policy.setAlarmCooldownSeconds(300);
        if (policy.getEnabled() == null) policy.setEnabled(false);
        if (policy.getId() == null)
        {
            policy.setCreateTime(now);
            policy.setCreateBy(SecurityUtils.getUsername());
        }
        policy.setUpdateTime(now);
        return policy.getId() == null ? policyMapper.insert(policy) : policyMapper.updateById(policy);
    }

    @Transactional
    public int remove(Long id)
    {
        return getScoped(id) == null ? 0 : policyMapper.deleteById(id);
    }

    public Map<String, Object> status()
    {
        List<RealtimeDiagnosisPolicyEntity> policies = list(null, null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyCount", policies.size());
        result.put("enabledPolicyCount", policies.stream().filter(p -> Boolean.TRUE.equals(p.getEnabled())).count());
        long gearQueue = dispatchService.queueDepth("gear");
        long bearingQueue = dispatchService.queueDepth("bearing");
        long gearPending = dispatchService.pendingCount("gear");
        long bearingPending = dispatchService.pendingCount("bearing");
        result.put("bufferedChannels", windowBuffer.bufferedChannels());
        result.put("queueDepth", gearQueue + bearingQueue);
        result.put("pendingCount", gearPending + bearingPending);
        result.put("gearQueueDepth", gearQueue);
        result.put("bearingQueueDepth", bearingQueue);
        result.put("gearPendingCount", gearPending);
        result.put("bearingPendingCount", bearingPending);
        result.put("degraded", gearPending + bearingPending > 0);
        return result;
    }

    private void validate(RealtimeDiagnosisPolicyEntity policy)
    {
        if (policy == null || policy.getDeviceId() == null || policy.getPointId() == null)
            throw new IllegalArgumentException("设备和测点不能为空");
        String type = policy.getModelType() == null ? "" : policy.getModelType().trim().toLowerCase();
        if (!MODEL_TYPES.contains(type)) throw new IllegalArgumentException("模型类型必须为 gear 或 bearing");
        policy.setModelType(type);
        if (policy.getWindowSamples() != null && (policy.getWindowSamples() < 1024 || policy.getWindowSamples() > 262144))
            throw new IllegalArgumentException("窗口样本数必须在 1024 到 262144 之间");
        if (policy.getStrideSamples() != null && policy.getStrideSamples() < 1)
            throw new IllegalArgumentException("步长必须大于零");
        if (policy.getMinIntervalSeconds() != null && policy.getMinIntervalSeconds() < 1)
            throw new IllegalArgumentException("最小间隔必须大于零");
        if (policy.getAlarmCooldownSeconds() != null && policy.getAlarmCooldownSeconds() < 0)
            throw new IllegalArgumentException("告警冷却时间不能为负数");
    }

    private List<PhmDeviceEntity> accessibleDevices()
    {
        return dataScopeService.listDevices(new PhmDeviceScopeQuery());
    }

    private Set<Long> accessibleDeviceIds()
    {
        return accessibleDevices().stream().map(PhmDeviceEntity::getId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }
}
