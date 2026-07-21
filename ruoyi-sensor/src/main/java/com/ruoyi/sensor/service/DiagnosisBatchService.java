package com.ruoyi.sensor.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.sensor.domain.entity.DiagnosisBatchEntity;
import com.ruoyi.sensor.domain.entity.InferenceTaskEntity;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.mapper.DiagnosisBatchMapper;
import com.ruoyi.sensor.mapper.InferenceTaskMapper;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional parent/child orchestration for independent point diagnosis tasks. */
@Service
public class DiagnosisBatchService
{
    private static final Set<String> FAILED_STATUSES = Set.of("FAILED", "INVALID");
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "INVALID");

    private final DiagnosisBatchMapper batchMapper;
    private final InferenceTaskMapper taskMapper;
    private final PhmMeasurePointMapper pointMapper;
    private final PhmAttachmentStorageService attachmentStorageService;
    private final PhmDataScopeService dataScopeService;
    private final int maxPoints;

    public DiagnosisBatchService(DiagnosisBatchMapper batchMapper,
        InferenceTaskMapper taskMapper,
        PhmMeasurePointMapper pointMapper,
        PhmAttachmentStorageService attachmentStorageService,
        PhmDataScopeService dataScopeService,
        @Value("${sensor.diagnosis.batch.max-points:8}") int maxPoints)
    {
        this.batchMapper = batchMapper;
        this.taskMapper = taskMapper;
        this.pointMapper = pointMapper;
        this.attachmentStorageService = attachmentStorageService;
        this.dataScopeService = dataScopeService;
        this.maxPoints = Math.max(1, maxPoints);
    }

    @Transactional(rollbackFor = Exception.class)
    public BatchCreation create(PhmDeviceEntity device, String modelType, String modelVersion,
        String clientRequestId, List<Map<String, Object>> items, String username)
    {
        if (device == null)
        {
            throw new IllegalArgumentException("无权访问指定设备");
        }
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128)
        {
            throw new IllegalArgumentException("clientRequestId 为必填项且不能超过 128 个字符");
        }
        List<ValidatedItem> validated = validateItems(device, items);
        String requestHash = requestHash(device.getDeviceCode(), modelType, modelVersion, validated);
        DiagnosisBatchEntity existing = batchMapper.selectOne(new LambdaQueryWrapper<DiagnosisBatchEntity>()
            .eq(DiagnosisBatchEntity::getCreatedBy, username)
            .eq(DiagnosisBatchEntity::getClientRequestId, clientRequestId)
            .last("LIMIT 1"));
        if (existing != null)
        {
            if (!requestHash.equals(existing.getRequestHash()))
            {
                throw new IllegalArgumentException("clientRequestId 已用于其他诊断批次");
            }
            return new BatchCreation(existing, latestTasks(existing.getId()), true);
        }

        Date now = new Date();
        DiagnosisBatchEntity batch = new DiagnosisBatchEntity();
        batch.setClientRequestId(clientRequestId);
        batch.setRequestHash(requestHash);
        batch.setDeviceCode(device.getDeviceCode());
        batch.setModelType(modelType);
        batch.setModelVersion(modelVersion);
        batch.setStatus("PENDING");
        batch.setTotalCount(validated.size());
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        batch.setCreatedBy(username);
        batch.setCreateTime(now);
        batch.setUpdateTime(now);
        try
        {
            batchMapper.insert(batch);
        }
        catch (DuplicateKeyException duplicate)
        {
            DiagnosisBatchEntity concurrent = batchMapper.selectOne(new LambdaQueryWrapper<DiagnosisBatchEntity>()
                .eq(DiagnosisBatchEntity::getCreatedBy, username)
                .eq(DiagnosisBatchEntity::getClientRequestId, clientRequestId)
                .last("LIMIT 1 FOR UPDATE"));
            if (concurrent != null && requestHash.equals(concurrent.getRequestHash()))
            {
                return new BatchCreation(concurrent, latestTasks(concurrent.getId()), true);
            }
            if (concurrent != null)
            {
                throw new IllegalArgumentException("clientRequestId 已用于其他诊断批次", duplicate);
            }
            throw duplicate;
        }

        List<InferenceTaskEntity> tasks = new ArrayList<>();
        for (ValidatedItem item : validated)
        {
            tasks.add(insertTask(batch, item, 1, null, username));
        }
        return new BatchCreation(batch, tasks, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public BatchCreation retryFailed(Long batchId, String username)
    {
        DiagnosisBatchEntity batch = authorizedBatch(batchId);
        List<InferenceTaskEntity> latest = latestTasks(batchId);
        List<InferenceTaskEntity> retries = new ArrayList<>();
        for (InferenceTaskEntity failed : latest)
        {
            if (!FAILED_STATUSES.contains(failed.getStatus()))
            {
                continue;
            }
            PhmAttachmentEntity attachment = attachmentStorageService.getAccessibleDiagnosisInput(
                toLong(failed.getInputRef()));
            if (attachment == null || attachment.getPointId() == null
                || !attachment.getPointId().equals(failed.getPointId()))
            {
                continue;
            }
            PhmMeasurePointEntity point = pointMapper.selectById(failed.getPointId());
            if (point == null || Boolean.FALSE.equals(point.getEnabled()))
            {
                continue;
            }
            ValidatedItem item = new ValidatedItem(point, attachment);
            retries.add(insertTask(batch, item,
                Math.max(1, failed.getAttemptNo() == null ? 1 : failed.getAttemptNo()) + 1,
                failed.getId(), username));
        }
        if (retries.isEmpty())
        {
            throw new IllegalArgumentException("当前批次没有可重试的失败测点");
        }
        batch.setStatus("RUNNING");
        batch.setFinishTime(null);
        batch.setUpdateTime(new Date());
        batchMapper.updateById(batch);
        return new BatchCreation(batch, retries, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public DiagnosisBatchEntity refresh(Long batchId)
    {
        DiagnosisBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null)
        {
            return null;
        }
        List<InferenceTaskEntity> latest = latestTasks(batchId);
        int success = (int) latest.stream().filter(task -> "SUCCEEDED".equals(task.getStatus())).count();
        int failed = (int) latest.stream().filter(task -> FAILED_STATUSES.contains(task.getStatus())).count();
        boolean allTerminal = !latest.isEmpty()
            && latest.stream().allMatch(task -> TERMINAL_STATUSES.contains(task.getStatus()));
        String status;
        if (!allTerminal)
        {
            status = latest.stream().allMatch(task -> "PENDING".equals(task.getStatus())) ? "PENDING" : "RUNNING";
        }
        else if (success == latest.size())
        {
            status = "SUCCEEDED";
        }
        else if (success == 0)
        {
            status = "FAILED";
        }
        else
        {
            status = "PARTIAL";
        }
        Date now = new Date();
        batch.setStatus(status);
        batch.setSuccessCount(success);
        batch.setFailedCount(failed);
        if (batch.getStartTime() == null && latest.stream().anyMatch(task -> task.getStartTime() != null))
        {
            batch.setStartTime(now);
        }
        batch.setFinishTime(allTerminal ? now : null);
        batch.setUpdateTime(now);
        batchMapper.updateById(batch);
        return batch;
    }

    public Map<String, Object> summary(Long batchId)
    {
        DiagnosisBatchEntity batch = authorizedBatch(batchId);
        List<InferenceTaskEntity> tasks = latestTasks(batchId);
        Map<Long, PhmMeasurePointEntity> points = pointMapper.selectBatchIds(
            tasks.stream().map(InferenceTaskEntity::getPointId).filter(java.util.Objects::nonNull).toList())
            .stream().collect(Collectors.toMap(PhmMeasurePointEntity::getId, point -> point));
        List<Map<String, Object>> itemSummaries = tasks.stream().map(task -> {
            PhmMeasurePointEntity point = points.get(task.getPointId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pointId", task.getPointId());
            item.put("pointCode", point == null ? null : point.getPointCode());
            item.put("pointName", point == null ? null : point.getPointName());
            item.put("channelId", task.getChannelId());
            item.put("attachmentId", toLong(task.getInputRef()));
            item.put("taskId", task.getId());
            item.put("attemptNo", task.getAttemptNo());
            item.put("status", task.getStatus());
            item.put("errorCode", task.getErrorCode());
            item.put("errorMessage", task.getErrorMessage());
            item.put("result", parseResult(task.getResultJson()));
            return item;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", batch.getId());
        result.put("clientRequestId", batch.getClientRequestId());
        result.put("deviceCode", batch.getDeviceCode());
        result.put("modelType", batch.getModelType());
        result.put("modelVersion", batch.getModelVersion());
        result.put("status", batch.getStatus());
        result.put("totalCount", batch.getTotalCount());
        result.put("successCount", batch.getSuccessCount());
        result.put("failedCount", batch.getFailedCount());
        result.put("createdAt", batch.getCreateTime());
        result.put("startedAt", batch.getStartTime());
        result.put("finishedAt", batch.getFinishTime());
        result.put("items", itemSummaries);
        return result;
    }

    public int getMaxPoints()
    {
        return maxPoints;
    }

    private List<ValidatedItem> validateItems(PhmDeviceEntity device, List<Map<String, Object>> items)
    {
        if (items == null || items.isEmpty() || items.size() > maxPoints)
        {
            throw new IllegalArgumentException("诊断批次必须包含 1 至 " + maxPoints + " 个测点");
        }
        Set<Long> pointIds = new HashSet<>();
        Set<Long> attachmentIds = new HashSet<>();
        List<ValidatedItem> result = new ArrayList<>();
        for (Map<String, Object> raw : items)
        {
            Long pointId = toLong(raw == null ? null : raw.get("pointId"));
            Long attachmentId = toLong(raw == null ? null : raw.get("attachmentId"));
            if (pointId == null || attachmentId == null)
            {
                throw new IllegalArgumentException("每个测点都必须指定 pointId 和 attachmentId");
            }
            if (!pointIds.add(pointId))
            {
                throw new IllegalArgumentException("诊断批次包含重复测点");
            }
            if (!attachmentIds.add(attachmentId))
            {
                throw new IllegalArgumentException("同一附件不能映射到多个测点");
            }
            PhmMeasurePointEntity point = pointMapper.selectById(pointId);
            if (point == null || !device.getId().equals(point.getDeviceId())
                || Boolean.FALSE.equals(point.getEnabled())
                || !"vibration".equalsIgnoreCase(String.valueOf(point.getSignalType()))
                || point.getChannelId() == null)
            {
                throw new IllegalArgumentException("测点不存在、已停用或不属于所选设备");
            }
            PhmAttachmentEntity attachment = attachmentStorageService.getAccessibleDiagnosisInput(attachmentId);
            if (attachment == null || !device.getId().equals(attachment.getBizId()))
            {
                throw new IllegalArgumentException("诊断附件不存在或与设备不匹配");
            }
            if (attachment.getPointId() == null || !pointId.equals(attachment.getPointId())
                || (attachment.getChannelId() != null && !point.getChannelId().equals(attachment.getChannelId())))
            {
                throw new IllegalArgumentException("诊断附件未绑定所选测点，历史未绑定附件不能用于多测点批次");
            }
            result.add(new ValidatedItem(point, attachment));
        }
        result.sort(Comparator.comparing(item -> item.point.getId()));
        return result;
    }

    private InferenceTaskEntity insertTask(DiagnosisBatchEntity batch, ValidatedItem item,
        int attemptNo, Long supersedesTaskId, String username)
    {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("batchId", batch.getId());
        input.put("deviceCode", batch.getDeviceCode());
        input.put("pointId", item.point.getId());
        input.put("channelId", item.point.getChannelId());
        input.put("attachmentId", item.attachment.getId());
        input.put("modelType", batch.getModelType());
        input.put("modelVersion", batch.getModelVersion());
        input.put("analysisMode", "batch");
        Date now = new Date();
        InferenceTaskEntity task = new InferenceTaskEntity();
        task.setRequestId(UUID.randomUUID().toString());
        task.setIdempotencyKey("batch:" + batch.getId() + ":point:" + item.point.getId() + ":attempt:" + attemptNo);
        task.setBatchId(batch.getId());
        task.setAttemptNo(attemptNo);
        task.setSupersedesTaskId(supersedesTaskId);
        task.setDeviceCode(batch.getDeviceCode());
        task.setPointId(item.point.getId());
        task.setChannelId(item.point.getChannelId());
        task.setModelType(batch.getModelType());
        task.setRequestedModelVersion(batch.getModelVersion());
        task.setInputType("ATTACHMENT");
        task.setInputRef(String.valueOf(item.attachment.getId()));
        task.setInputSha256(item.attachment.getSha256());
        task.setStatus("PENDING");
        task.setInputJson(JSON.toJSONString(input));
        task.setCreatedBy(username);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        taskMapper.insert(task);
        return task;
    }

    private DiagnosisBatchEntity authorizedBatch(Long batchId)
    {
        DiagnosisBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null)
        {
            throw new IllegalArgumentException("诊断批次不存在");
        }
        PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
        query.setDeviceCode(batch.getDeviceCode());
        if (dataScopeService.getDevice(query) == null)
        {
            throw new IllegalArgumentException("诊断批次不存在");
        }
        return batch;
    }

    private List<InferenceTaskEntity> latestTasks(Long batchId)
    {
        List<InferenceTaskEntity> all = taskMapper.selectList(new LambdaQueryWrapper<InferenceTaskEntity>()
            .eq(InferenceTaskEntity::getBatchId, batchId)
            .orderByAsc(InferenceTaskEntity::getPointId)
            .orderByDesc(InferenceTaskEntity::getAttemptNo)
            .orderByDesc(InferenceTaskEntity::getCreateTime));
        Map<Long, InferenceTaskEntity> latest = new LinkedHashMap<>();
        for (InferenceTaskEntity task : all)
        {
            latest.putIfAbsent(task.getPointId(), task);
        }
        return new ArrayList<>(latest.values());
    }

    private String requestHash(String deviceCode, String modelType, String modelVersion,
        List<ValidatedItem> items)
    {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("deviceCode", deviceCode);
        canonical.put("modelType", modelType);
        canonical.put("modelVersion", modelVersion);
        canonical.put("items", items.stream().map(item -> Map.of(
            "pointId", item.point.getId(), "attachmentId", item.attachment.getId())).toList());
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.toJSONString(canonical).getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("无法生成诊断批次校验值", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResult(String json)
    {
        if (json == null || json.isBlank())
        {
            return null;
        }
        try
        {
            return JSON.parseObject(json, Map.class);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private Long toLong(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        try
        {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static final class ValidatedItem
    {
        private final PhmMeasurePointEntity point;
        private final PhmAttachmentEntity attachment;

        private ValidatedItem(PhmMeasurePointEntity point, PhmAttachmentEntity attachment)
        {
            this.point = point;
            this.attachment = attachment;
        }
    }

    public static final class BatchCreation
    {
        private final DiagnosisBatchEntity batch;
        private final List<InferenceTaskEntity> tasks;
        private final boolean duplicate;

        private BatchCreation(DiagnosisBatchEntity batch, List<InferenceTaskEntity> tasks, boolean duplicate)
        {
            this.batch = batch;
            this.tasks = tasks;
            this.duplicate = duplicate;
        }

        public DiagnosisBatchEntity getBatch() { return batch; }
        public List<InferenceTaskEntity> getTasks() { return tasks; }
        public boolean isDuplicate() { return duplicate; }
    }
}
