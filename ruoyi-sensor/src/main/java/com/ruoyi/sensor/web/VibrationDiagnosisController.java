package com.ruoyi.sensor.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import com.ruoyi.sensor.domain.entity.InferenceTaskEntity;
import com.ruoyi.sensor.domain.entity.ModelReleaseEntity;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.mapper.InferenceTaskMapper;
import com.ruoyi.sensor.mapper.ModelReleaseMapper;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.service.PhmDataScopeService;
import com.ruoyi.sensor.service.PhmAttachmentStorageService;
import com.ruoyi.sensor.service.DiagnosisBatchService;
import com.ruoyi.sensor.domain.entity.VibrationAnalysisBatchEntity;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.vo.SensorWebSocketMessageVo;
import com.ruoyi.sensor.service.SensorWebSocketPushService;
import com.ruoyi.sensor.service.PhmService;
import com.ruoyi.sensor.service.VibrationAnalysisBatchService;
import com.ruoyi.sensor.service.timeseries.TimeSeriesAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.MDC;

@RestController
@RequestMapping({"/sensor/diagnosis", "/sensor/vibration"})
public class VibrationDiagnosisController
{
    private static final String WS_EVENT_ANALYSIS = "analysis";
    private static final String WS_EVENT_REALTIME = "realtime";
    private static final Set<String> SUPPORTED_MODEL_TYPES = Set.of("gear", "bearing");
    private static final Set<String> EXECUTABLE_MODEL_STATUSES = Set.of("ACTIVE", "VALIDATED", "RETIRED");

    private final TimeSeriesAnalysisService timeSeriesAnalysisService;
    private final VibrationAnalysisBatchService batchService;
    private final SensorWebSocketPushService webSocketPushService;
    private final PhmService phmService;
    private final PhmAttachmentStorageService attachmentStorageService;
    private final String gearInferUrl;
    private final String bearingInferUrl;
    private final String internalToken;
    private final String defaultDeviceCode;
    private final String defaultModelType;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final RestClient restClient;

    @Autowired
    private InferenceTaskMapper inferenceTaskMapper;

    @Autowired
    @Qualifier("diagnosisExecutor")
    private Executor diagnosisExecutor;

    @Autowired
    private DiagnosisBatchService diagnosisBatchService;

    @Value("${sensor.diagnosis.multi-point-enabled:false}")
    private boolean multiPointEnabled;

    @Value("${sensor.diagnosis.batch.max-points:8}")
    private int diagnosisBatchMaxPoints = 8;

    @Autowired
    private PhmDataScopeService dataScopeService;

    @Autowired
    private ModelReleaseMapper modelReleaseMapper;

    public VibrationDiagnosisController(TimeSeriesAnalysisService timeSeriesAnalysisService,
        VibrationAnalysisBatchService batchService,
        SensorWebSocketPushService webSocketPushService,
        PhmService phmService,
        PhmAttachmentStorageService attachmentStorageService,
        @Value("${sensor.inference.gear-url:}") String gearInferUrl,
        @Value("${sensor.inference.bearing-url:}") String bearingInferUrl,
        @Value("${sensor.inference.internal-token:}") String internalToken,
        @Value("${sensor.inference.default-device-code:}") String defaultDeviceCode,
        @Value("${sensor.inference.default-model-type:gear}") String defaultModelType,
        @Value("${sensor.inference.connect-timeout-ms:5000}") int connectTimeoutMs,
        @Value("${sensor.inference.read-timeout-ms:120000}") int readTimeoutMs)
    {
        this.timeSeriesAnalysisService = timeSeriesAnalysisService;
        this.batchService = batchService;
        this.webSocketPushService = webSocketPushService;
        this.phmService = phmService;
        this.attachmentStorageService = attachmentStorageService;
        this.gearInferUrl = gearInferUrl;
        this.bearingInferUrl = bearingInferUrl;
        this.internalToken = internalToken;
        this.defaultDeviceCode = defaultDeviceCode;
        this.defaultModelType = defaultModelType;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:run')")
    @PostMapping("/receiver/analyze")
    public AjaxResult receiverAnalyze(@RequestBody Map<String, Object> payload)
    {
        String deviceCode = resolveDeviceCode(payload);

        try {
            Map<String, Object> trustedPayload = withTrustedAttachment(payload);
            Map<String, Object> pythonResult = callPythonInfer(trustedPayload);
            validatePythonResult(pythonResult);
            validateRequestedModel(pythonResult,
                stringValue(trustedPayload.get("modelType"), defaultModelType),
                stringValue(trustedPayload.get("modelVersion"), ""));
            Map<String, Object> normalized = normalizePythonResult(pythonResult, deviceCode,
                stringValue(trustedPayload.get("filename"), null), trustedPayload);
            phmService.syncDiagnosisResult(normalized);
            phmService.recalculateDiagnosisState(deviceCode);
            pushDiagnosis(normalized);
            return AjaxResult.success(normalized);
        } catch (Exception ex) {
            Map<String, Object> failure = buildFailureResult(deviceCode, null, ex.getMessage());
            pushDiagnosis(failure);
            return AjaxResult.error("推理失败: " + ex.getMessage()).put("data", failure);
        }
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/options")
    public AjaxResult diagnosisOptions()
    {
        List<PhmDeviceEntity> devices = dataScopeService.listDevices(new PhmDeviceScopeQuery());
        List<Map<String, Object>> deviceOptions = devices.stream().map(device -> {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", device.getId());
            option.put("deviceCode", device.getDeviceCode());
            option.put("deviceName", device.getDeviceName());
            option.put("deviceType", device.getDeviceType());
            option.put("deptId", device.getDeptId());
            option.put("deptName", device.getDeptName());
            option.put("orgName", device.getOrgName());
            option.put("status", device.getStatus());
            option.put("location", device.getLocation());
            return option;
        }).collect(java.util.stream.Collectors.toList());
        Map<Long, PhmDeviceEntity> accessibleDevicesById = devices.stream()
            .filter(device -> device.getId() != null)
            .collect(java.util.stream.Collectors.toMap(PhmDeviceEntity::getId, device -> device,
                (left, right) -> left, LinkedHashMap::new));

        List<Map<String, Object>> pointOptions = phmService.listMeasurePoints(null).stream()
            .filter(point -> accessibleDevicesById.containsKey(point.getDeviceId()))
            .filter(point -> !Boolean.FALSE.equals(point.getEnabled()))
            .filter(point -> "vibration".equalsIgnoreCase(String.valueOf(point.getSignalType()).trim()))
            .map(point -> {
                PhmDeviceEntity owner = accessibleDevicesById.get(point.getDeviceId());
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", point.getId());
                option.put("deviceId", point.getDeviceId());
                option.put("deviceCode", owner.getDeviceCode());
                option.put("deviceName", owner.getDeviceName());
                option.put("pointCode", point.getPointCode());
                option.put("pointName", point.getPointName());
                option.put("channelId", point.getChannelId());
                option.put("signalType", point.getSignalType());
                option.put("enabled", point.getEnabled());
                return option;
            }).collect(java.util.stream.Collectors.toList());

        List<ModelReleaseEntity> releases = modelReleaseMapper.selectList(
            new LambdaQueryWrapper<ModelReleaseEntity>()
                .in(ModelReleaseEntity::getModelType, SUPPORTED_MODEL_TYPES)
                .in(ModelReleaseEntity::getStatus, EXECUTABLE_MODEL_STATUSES)
                .orderByAsc(ModelReleaseEntity::getModelType)
                .orderByDesc(ModelReleaseEntity::getCreateTime));
        List<Map<String, Object>> versionOptions = releases.stream().map(release -> {
            Map<String, Object> option = new LinkedHashMap<>();
            boolean available = hasText(release.getArtifactUri()) && hasText(release.getFileSha256());
            option.put("id", release.getId());
            option.put("modelName", release.getModelName());
            option.put("modelType", release.getModelType());
            option.put("semanticVersion", release.getSemanticVersion());
            option.put("status", release.getStatus());
            option.put("available", available);
            option.put("unavailableReason", available ? null : "模型制品或校验信息未登记");
            return option;
        }).collect(java.util.stream.Collectors.toList());

        mergeRuntimeModelOption(versionOptions, "gear");
        mergeRuntimeModelOption(versionOptions, "bearing");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("devices", deviceOptions);
        result.put("points", pointOptions);
        result.put("modelTypes", List.of(
            Map.of("value", "gear", "label", "齿轮诊断模型"),
            Map.of("value", "bearing", "label", "轴承诊断模型")));
        result.put("modelVersions", versionOptions);
        result.put("multiPointEnabled", multiPointEnabled);
        result.put("maxBatchPoints", Math.max(1, diagnosisBatchMaxPoints));
        return AjaxResult.success(result);
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/overview")
    public AjaxResult diagnosisOverview()
    {
        List<PhmDeviceEntity> devices = new ArrayList<>(
            dataScopeService.listDevices(new PhmDeviceScopeQuery()));
        devices.sort(Comparator
            .comparing((PhmDeviceEntity device) -> hasText(device.getDeptName())
                ? device.getDeptName() : "未分配部门")
            .thenComparing(device -> stringValue(device.getDeviceCode(), "")));

        Map<Long, PhmDeviceEntity> devicesById = new LinkedHashMap<>();
        for (PhmDeviceEntity device : devices)
        {
            if (device.getId() != null)
            {
                devicesById.put(device.getId(), device);
            }
        }

        List<PhmMeasurePointEntity> points = phmService.listMeasurePoints(null).stream()
            .filter(point -> devicesById.containsKey(point.getDeviceId()))
            .filter(point -> !Boolean.FALSE.equals(point.getEnabled()))
            .filter(point -> "vibration".equalsIgnoreCase(stringValue(point.getSignalType(), "").trim()))
            .sorted(Comparator
                .comparing(PhmMeasurePointEntity::getDisplayOrder,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PhmMeasurePointEntity::getChannelId,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(point -> stringValue(point.getPointCode(), "")))
            .toList();

        List<Long> pointIds = points.stream()
            .map(PhmMeasurePointEntity::getId)
            .filter(java.util.Objects::nonNull)
            .toList();
        List<EnhancedInferenceRecordEntity> latestRecords = pointIds.isEmpty()
            ? List.of() : phmService.listLatestDiagnosesByPointIds(pointIds);
        Map<Long, EnhancedInferenceRecordEntity> latestByPointId = new LinkedHashMap<>();
        if (latestRecords != null)
        {
            for (EnhancedInferenceRecordEntity record : latestRecords)
            {
                if (record != null && record.getPointId() != null)
                {
                    latestByPointId.put(record.getPointId(), record);
                }
            }
        }

        Map<Long, List<PhmMeasurePointEntity>> pointsByDevice = new LinkedHashMap<>();
        for (PhmMeasurePointEntity point : points)
        {
            pointsByDevice.computeIfAbsent(point.getDeviceId(), ignored -> new ArrayList<>()).add(point);
        }

        Map<String, Map<String, Object>> departmentsByKey = new LinkedHashMap<>();
        int visibleDeviceCount = 0;
        for (PhmDeviceEntity device : devices)
        {
            List<PhmMeasurePointEntity> devicePoints = pointsByDevice.get(device.getId());
            if (devicePoints == null || devicePoints.isEmpty())
            {
                continue;
            }
            String departmentKey = device.getDeptId() == null
                ? "unassigned" : "dept-" + device.getDeptId();
            Map<String, Object> department = departmentsByKey.computeIfAbsent(departmentKey, ignored -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("deptId", device.getDeptId());
                row.put("deptName", hasText(device.getDeptName()) ? device.getDeptName() : "未分配部门");
                row.put("devices", new ArrayList<Map<String, Object>>());
                return row;
            });

            Map<String, Object> deviceRow = new LinkedHashMap<>();
            deviceRow.put("id", device.getId());
            deviceRow.put("deviceCode", device.getDeviceCode());
            deviceRow.put("deviceName", device.getDeviceName());
            deviceRow.put("deviceType", device.getDeviceType());
            deviceRow.put("orgName", device.getOrgName());
            deviceRow.put("location", device.getLocation());
            deviceRow.put("status", device.getStatus());
            List<Map<String, Object>> pointRows = new ArrayList<>();
            for (PhmMeasurePointEntity point : devicePoints)
            {
                Map<String, Object> pointRow = new LinkedHashMap<>();
                pointRow.put("id", point.getId());
                pointRow.put("pointCode", point.getPointCode());
                pointRow.put("pointName", point.getPointName());
                pointRow.put("channelId", point.getChannelId());
                pointRow.put("signalType", point.getSignalType());
                pointRow.put("enabled", point.getEnabled());

                EnhancedInferenceRecordEntity latest = latestByPointId.get(point.getId());
                Map<String, Object> diagnosis = new LinkedHashMap<>();
                diagnosis.put("dataStatus", latest == null ? "no_data" : "available");
                if (latest != null)
                {
                    diagnosis.put("diagnosisResult", latest.getDiagnosisResult());
                    diagnosis.put("riskLevel", latest.getRiskLevel());
                    diagnosis.put("alarmLevel", latest.getAlarmLevel());
                    diagnosis.put("healthIndex", latest.getHealthIndex());
                    diagnosis.put("confidence", latest.getConfidence());
                    diagnosis.put("diagnosisTime",
                        latest.getSampleTime() == null ? latest.getCreateTime() : latest.getSampleTime());
                }
                pointRow.put("latestDiagnosis", diagnosis);
                pointRows.add(pointRow);
            }
            deviceRow.put("points", pointRows);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> departmentDevices =
                (List<Map<String, Object>>) department.get("devices");
            departmentDevices.add(deviceRow);
            visibleDeviceCount++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("departments", new ArrayList<>(departmentsByKey.values()));
        result.put("departmentCount", departmentsByKey.size());
        result.put("deviceCount", visibleDeviceCount);
        result.put("pointCount", points.size());
        return AjaxResult.success(result);
    }

    /**
     * Creates an automatic task after the MAT receiver has already validated
     * the device, point, channel and secure attachment. This method deliberately
     * does not depend on an HTTP login context.
     */
    public Map<String, Object> submitInternalMatTask(String deviceCode, Long pointId, Integer channelId,
        PhmAttachmentEntity attachment, String modelType, String modelVersion, Date acquisitionTime)
    {
        if (attachment == null || attachment.getId() == null)
        {
            throw new IllegalArgumentException("MAT 附件不能为空");
        }
        if (!SUPPORTED_MODEL_TYPES.contains(modelType))
        {
            throw new IllegalArgumentException("MAT 测点模型类型非法");
        }
        ResolvedModel resolved = resolveModel(modelType, modelVersion);
        String idempotencyKey = "MAT_TCP:" + deviceCode + ":" + pointId + ":" + attachment.getSha256();
        InferenceTaskEntity existing = inferenceTaskMapper.selectOne(
            new LambdaQueryWrapper<InferenceTaskEntity>()
                .eq(InferenceTaskEntity::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null)
        {
            return taskSummary(existing);
        }
        Date now = new Date();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceCode", deviceCode);
        payload.put("pointId", pointId);
        payload.put("channelId", channelId);
        payload.put("attachmentId", attachment.getId());
        payload.put("filename", attachment.getFileName());
        payload.put("sourceType", "MAT_TCP");
        payload.put("sampleTime", acquisitionTime == null ? now : acquisitionTime);
        payload.put("modelType", modelType);
        payload.put("modelVersion", resolved.modelVersion);
        putResolvedModel(payload, resolved);

        InferenceTaskEntity task = new InferenceTaskEntity();
        task.setRequestId(UUID.randomUUID().toString());
        task.setIdempotencyKey(idempotencyKey);
        task.setAttemptNo(1);
        task.setDeviceCode(deviceCode);
        task.setPointId(pointId);
        task.setChannelId(channelId);
        task.setModelType(modelType);
        task.setRequestedModelVersion(resolved.modelVersion);
        task.setInputType("ATTACHMENT");
        task.setInputRef(String.valueOf(attachment.getId()));
        task.setInputSha256(attachment.getSha256());
        task.setSourceType("MAT_TCP");
        task.setStatus("PENDING");
        task.setInputJson(JSON.toJSONString(payload));
        task.setCreatedBy("mat-tcp");
        task.setCreateTime(now);
        task.setUpdateTime(now);
        inferenceTaskMapper.insert(task);
        diagnosisExecutor.execute(() -> executeTask(task.getId()));
        return taskSummary(task);
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:run')")
    @PostMapping("/tasks")
    public AjaxResult createTask(@RequestBody Map<String, Object> payload)
    {
        if (payload == null || payload.get("attachmentId") == null)
        {
            return AjaxResult.error("attachmentId 为必填项，禁止提交任意服务器文件路径");
        }
        if (!hasText(payload.get("deviceCode")))
        {
            return AjaxResult.error("deviceCode 为必填项");
        }
        PhmDeviceScopeQuery deviceQuery = new PhmDeviceScopeQuery();
        deviceQuery.setDeviceCode(String.valueOf(payload.get("deviceCode")).trim());
        PhmDeviceEntity device = dataScopeService.getDevice(deviceQuery);
        if (device == null)
        {
            return AjaxResult.error("无权访问指定设备");
        }
        String modelType = stringValue(payload.get("modelType"), defaultModelType);
        if (!SUPPORTED_MODEL_TYPES.contains(modelType))
        {
            return AjaxResult.error("modelType 仅支持 gear 或 bearing");
        }
        PhmMeasurePointEntity point;
        ResolvedModel resolvedModel;
        try
        {
            point = resolveDiagnosisPoint(device, toLong(payload.get("pointId"), null));
            resolvedModel = resolveModel(modelType, stringValue(payload.get("modelVersion"), null));
        }
        catch (IllegalArgumentException ex)
        {
            return AjaxResult.error(ex.getMessage());
        }
        Long attachmentId = toLong(payload.get("attachmentId"), null);
        PhmAttachmentEntity attachment = attachmentStorageService.getAccessibleDiagnosisInput(attachmentId);
        if (attachment == null)
        {
            return AjaxResult.error("诊断输入附件不存在或无权访问");
        }
        if (attachment.getBizId() != null && !attachment.getBizId().equals(device.getId()))
        {
            return AjaxResult.error("诊断输入附件与设备不匹配");
        }
        if (attachment.getPointId() != null && point != null && !attachment.getPointId().equals(point.getId()))
        {
            return AjaxResult.error("诊断输入附件与测点不匹配");
        }

        String idempotencyKey = stringValue(payload.get("idempotencyKey"), null);
        if (idempotencyKey != null && !idempotencyKey.isBlank())
        {
            InferenceTaskEntity existing = inferenceTaskMapper.selectOne(
                new LambdaQueryWrapper<InferenceTaskEntity>()
                    .eq(InferenceTaskEntity::getIdempotencyKey, idempotencyKey)
                    .last("limit 1"));
            if (existing != null)
            {
                if (!taskBelongsTo(existing, device.getDeviceCode(), attachmentId))
                {
                    return AjaxResult.error("幂等键已被其他诊断任务占用");
                }
                return AjaxResult.success(taskSummary(existing));
            }
        }

        Date now = new Date();
        Map<String, Object> trustedPayload = new LinkedHashMap<>(payload);
        trustedPayload.remove("filePath");
        trustedPayload.remove("modelArtifactUri");
        trustedPayload.remove("modelArtifactSha256");
        trustedPayload.put("deviceCode", device.getDeviceCode());
        trustedPayload.put("modelType", modelType);
        trustedPayload.put("modelVersion", resolvedModel.modelVersion);
        if (point != null)
        {
            trustedPayload.put("pointId", point.getId());
            trustedPayload.put("channelId", point.getChannelId());
        }
        InferenceTaskEntity task = new InferenceTaskEntity();
        task.setRequestId(UUID.randomUUID().toString());
        task.setIdempotencyKey(idempotencyKey);
        task.setAttemptNo(1);
        task.setDeviceCode(String.valueOf(payload.get("deviceCode")).trim());
        task.setPointId(point == null ? null : point.getId());
        task.setChannelId(point == null ? null : point.getChannelId());
        task.setModelType(modelType);
        task.setRequestedModelVersion(resolvedModel.modelVersion);
        task.setInputType("ATTACHMENT");
        task.setInputRef(String.valueOf(attachmentId));
        task.setInputSha256(attachment.getSha256());
        task.setStatus("PENDING");
        task.setInputJson(JSON.toJSONString(trustedPayload));
        task.setCreatedBy(SecurityUtils.getUsername());
        task.setCreateTime(now);
        task.setUpdateTime(now);
        inferenceTaskMapper.insert(task);

        diagnosisExecutor.execute(() -> executeTask(task.getId()));
        return AjaxResult.success(taskSummary(task));
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:run')")
    @PostMapping("/batches")
    @SuppressWarnings("unchecked")
    public AjaxResult createDiagnosisBatch(@RequestBody Map<String, Object> payload)
    {
        if (!multiPointEnabled)
        {
            return AjaxResult.error("多测点诊断功能尚未启用");
        }
        try
        {
            if (payload == null || !(payload.get("items") instanceof List))
            {
                return AjaxResult.error("items 为必填项");
            }
            String deviceCode = stringValue(payload.get("deviceCode"), "").trim();
            PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
            query.setDeviceCode(deviceCode);
            PhmDeviceEntity device = dataScopeService.getDevice(query);
            if (device == null)
            {
                return AjaxResult.error("无权访问指定设备");
            }
            String modelType = stringValue(payload.get("modelType"), defaultModelType);
            ResolvedModel model = resolveModel(modelType, stringValue(payload.get("modelVersion"), null));
            List<Map<String, Object>> items = ((List<?>) payload.get("items")).stream()
                .filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
            DiagnosisBatchService.BatchCreation creation = diagnosisBatchService.create(
                device, modelType, model.modelVersion,
                stringValue(payload.get("clientRequestId"), null), items, SecurityUtils.getUsername());
            if (!creation.isDuplicate())
            {
                creation.getTasks().forEach(task -> diagnosisExecutor.execute(() -> executeTask(task.getId())));
            }
            Map<String, Object> summary = diagnosisBatchService.summary(creation.getBatch().getId());
            summary.put("duplicate", creation.isDuplicate());
            return AjaxResult.success(summary);
        }
        catch (Exception ex)
        {
            return AjaxResult.error("创建诊断批次失败: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/batches/{id}")
    public AjaxResult getDiagnosisBatch(@org.springframework.web.bind.annotation.PathVariable Long id)
    {
        try
        {
            return AjaxResult.success(diagnosisBatchService.summary(id));
        }
        catch (Exception ex)
        {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:run')")
    @PostMapping("/batches/{id}/retry")
    public AjaxResult retryDiagnosisBatch(@org.springframework.web.bind.annotation.PathVariable Long id)
    {
        if (!multiPointEnabled)
        {
            return AjaxResult.error("多测点诊断功能尚未启用");
        }
        try
        {
            DiagnosisBatchService.BatchCreation creation = diagnosisBatchService.retryFailed(
                id, SecurityUtils.getUsername());
            creation.getTasks().forEach(task -> diagnosisExecutor.execute(() -> executeTask(task.getId())));
            return AjaxResult.success(diagnosisBatchService.summary(id));
        }
        catch (Exception ex)
        {
            return AjaxResult.error("重试诊断批次失败: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/tasks/{id}")
    public AjaxResult getTask(@org.springframework.web.bind.annotation.PathVariable Long id)
    {
        InferenceTaskEntity task = inferenceTaskMapper.selectById(id);
        if (task == null)
        {
            return AjaxResult.error("诊断任务不存在");
        }
        PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
        query.setDeviceCode(task.getDeviceCode());
        return dataScopeService.getDevice(query) == null
            ? AjaxResult.error("诊断任务不存在") : AjaxResult.success(task);
    }

    private void executeTask(Long taskId)
    {
        InferenceTaskEntity task = inferenceTaskMapper.selectById(taskId);
        if (task == null || !"PENDING".equals(task.getStatus()))
        {
            return;
        }
        MDC.put("taskId", String.valueOf(task.getId()));
        MDC.put("requestId", task.getRequestId());
        MDC.put("deviceCode", task.getDeviceCode());
        try
        {
        task.setStatus("RUNNING");
        task.setStartTime(new Date());
        task.setUpdateTime(new Date());
        inferenceTaskMapper.updateById(task);

        Map<String, Object> payload = JSON.parseObject(task.getInputJson(), Map.class);
        payload.put("taskId", String.valueOf(task.getId()));
        payload.put("requestId", task.getRequestId());
        payload.put("modelType", task.getModelType());
        payload.put("modelVersion", task.getRequestedModelVersion());
        payload.put("deviceCode", task.getDeviceCode());
        try
        {
            putResolvedModel(payload, resolveModel(task.getModelType(), task.getRequestedModelVersion()));
        }
        catch (IllegalArgumentException ex)
        {
            finishTask(task, "INVALID", "MODEL_UNAVAILABLE", ex.getMessage(), null);
            return;
        }
        payload.putIfAbsent("sampleTime", DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, new Date()));
        PhmAttachmentEntity attachment = attachmentStorageService.getDiagnosisInputForTask(
            toLong(task.getInputRef(), null), task.getInputSha256());
        if (attachment == null)
        {
            finishTask(task, "INVALID", "INPUT_NOT_ACCESSIBLE",
                "诊断输入附件不存在或已失去访问权限", null);
            return;
        }
        try
        {
            payload.put("attachmentId", attachment.getId());
            payload.put("filePath", attachmentStorageService.trustedContentPath(attachment).toString());
            payload.put("filename", attachment.getFileName());
        }
        catch (IOException ex)
        {
            finishTask(task, "INVALID", "INPUT_UNAVAILABLE", ex.getMessage(), null);
            return;
        }

        Map<String, Object> pythonResult;
        try
        {
            pythonResult = callPythonInfer(payload);
        }
        catch (Exception ex)
        {
            finishTask(task, "FAILED", "INFERENCE_UNAVAILABLE", ex.getMessage(), null);
            pushDiagnosis(buildFailureResult(task.getDeviceCode(), attachment.getFileName(), ex.getMessage()));
            return;
        }

        try
        {
            validatePythonResult(pythonResult);
            validateRequestedModel(pythonResult, task.getModelType(), task.getRequestedModelVersion());
            Map<String, Object> normalized = normalizePythonResult(
                pythonResult, task.getDeviceCode(), attachment.getFileName(), payload);
            phmService.syncDiagnosisResult(normalized);
            pushDiagnosis(normalized);
            finishTask(task, "SUCCEEDED", null, null, normalized);
        }
        catch (Exception ex)
        {
            finishTask(task, "INVALID", "INVALID_RESULT", ex.getMessage(), pythonResult);
        }
        }
        finally
        {
            MDC.remove("taskId");
            MDC.remove("requestId");
            MDC.remove("deviceCode");
        }
    }

    private void finishTask(InferenceTaskEntity task, String status, String errorCode,
        String errorMessage, Object result)
    {
        task.setStatus(status);
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage == null ? null
            : errorMessage.substring(0, Math.min(errorMessage.length(), 1000)));
        task.setResultJson(result == null ? null : JSON.toJSONString(result));
        task.setFinishTime(new Date());
        task.setUpdateTime(new Date());
        inferenceTaskMapper.updateById(task);
        if (task.getBatchId() != null && diagnosisBatchService != null)
        {
            diagnosisBatchService.refresh(task.getBatchId());
        }
        phmService.recalculateDiagnosisState(task.getDeviceCode());
    }

    private Map<String, Object> taskSummary(InferenceTaskEntity task)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("requestId", task.getRequestId());
        result.put("batchId", task.getBatchId());
        result.put("attemptNo", task.getAttemptNo());
        result.put("status", task.getStatus());
        result.put("deviceCode", task.getDeviceCode());
        result.put("pointId", task.getPointId());
        result.put("modelType", task.getModelType());
        result.put("modelVersion", task.getRequestedModelVersion());
        result.put("createdAt", task.getCreateTime());
        return result;
    }

    private boolean hasText(Object value)
    {
        return value != null && !String.valueOf(value).isBlank();
    }

    private PhmMeasurePointEntity resolveDiagnosisPoint(PhmDeviceEntity device, Long pointId)
    {
        if (pointId == null)
        {
            return null;
        }
        PhmMeasurePointEntity point = phmService.listMeasurePoints(device.getId()).stream()
            .filter(item -> pointId.equals(item.getId()))
            .findFirst().orElse(null);
        if (point == null)
        {
            throw new IllegalArgumentException("测点不属于所选设备或无权访问");
        }
        if (Boolean.FALSE.equals(point.getEnabled()))
        {
            throw new IllegalArgumentException("所选测点已停用");
        }
        if (!"vibration".equalsIgnoreCase(String.valueOf(point.getSignalType()).trim()))
        {
            throw new IllegalArgumentException("所选测点不是振动测点");
        }
        if (point.getChannelId() == null)
        {
            throw new IllegalArgumentException("所选测点未配置采集通道");
        }
        return point;
    }

    private ResolvedModel resolveModel(String modelType, String requestedVersion)
    {
        if (!SUPPORTED_MODEL_TYPES.contains(modelType))
        {
            throw new IllegalArgumentException("modelType 仅支持 gear 或 bearing");
        }
        String version = hasText(requestedVersion) ? requestedVersion.trim() : null;
        LambdaQueryWrapper<ModelReleaseEntity> wrapper = new LambdaQueryWrapper<ModelReleaseEntity>()
            .eq(ModelReleaseEntity::getModelType, modelType)
            .in(ModelReleaseEntity::getStatus, EXECUTABLE_MODEL_STATUSES)
            .eq(version != null, ModelReleaseEntity::getSemanticVersion, version)
            .eq(version == null, ModelReleaseEntity::getStatus, "ACTIVE")
            .orderByDesc(ModelReleaseEntity::getCreateTime)
            .last("limit 1");
        ModelReleaseEntity release = modelReleaseMapper.selectOne(wrapper);
        if (release != null && hasText(release.getArtifactUri()) && hasText(release.getFileSha256()))
        {
            return new ResolvedModel(release.getSemanticVersion(), release);
        }

        RuntimeModel runtime = runtimeModel(modelType);
        if (runtime != null && runtime.available
            && (version == null || version.equals(runtime.version)))
        {
            return new ResolvedModel(runtime.version, null);
        }
        if (release != null)
        {
            throw new IllegalArgumentException("所选模型版本缺少可验证的模型制品");
        }
        throw new IllegalArgumentException(version == null
            ? "当前模型类型没有可执行版本" : "所选模型版本不存在、不可执行或已失去制品");
    }

    private void putResolvedModel(Map<String, Object> payload, ResolvedModel resolvedModel)
    {
        payload.put("modelVersion", resolvedModel.modelVersion);
        if (resolvedModel.release != null)
        {
            payload.put("modelArtifactUri", resolvedModel.release.getArtifactUri());
            payload.put("modelArtifactSha256", resolvedModel.release.getFileSha256());
        }
    }

    @SuppressWarnings("unchecked")
    private void validateRequestedModel(Map<String, Object> response, String modelType,
        String modelVersion) throws IOException
    {
        Map<String, Object> result = response != null && response.get("data") instanceof Map
            ? (Map<String, Object>) response.get("data") : response;
        if (result == null
            || !modelType.equalsIgnoreCase(stringValue(result.get("modelType"), ""))
            || !modelVersion.equals(stringValue(result.get("modelVersion"), "")))
        {
            throw new IOException("推理服务实际执行的模型与请求不一致");
        }
    }

    private void mergeRuntimeModelOption(List<Map<String, Object>> options, String modelType)
    {
        RuntimeModel runtime = runtimeModel(modelType);
        if (runtime == null || !hasText(runtime.version))
        {
            return;
        }
        Map<String, Object> existing = options.stream().filter(item -> modelType.equals(item.get("modelType"))
            && runtime.version.equals(item.get("semanticVersion"))).findFirst().orElse(null);
        if (existing != null)
        {
            if (runtime.available)
            {
                existing.put("available", true);
                existing.put("unavailableReason", null);
                existing.put("source", "RUNTIME");
            }
            return;
        }
        if (existing == null)
        {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", null);
            option.put("modelName", "gear".equals(modelType) ? "齿轮诊断模型" : "轴承诊断模型");
            option.put("modelType", modelType);
            option.put("semanticVersion", runtime.version);
            option.put("status", "ACTIVE");
            option.put("available", runtime.available);
            option.put("unavailableReason", runtime.available ? null : "当前推理服务未加载该模型");
            option.put("source", "RUNTIME");
            options.add(option);
        }
    }

    private RuntimeModel runtimeModel(String modelType)
    {
        try
        {
            Map<String, Object> health = proxyGet(modelType, "/internal/health/ready");
            String prefix = "bearing".equals(modelType) ? "bearing" : "gear";
            String version = stringValue(health.get(prefix + "_model_version"), null);
            boolean available = Boolean.TRUE.equals(health.get(prefix + "_model_loaded"));
            return hasText(version) ? new RuntimeModel(version, available) : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static final class ResolvedModel
    {
        private final String modelVersion;
        private final ModelReleaseEntity release;

        private ResolvedModel(String modelVersion, ModelReleaseEntity release)
        {
            this.modelVersion = modelVersion;
            this.release = release;
        }
    }

    private static final class RuntimeModel
    {
        private final String version;
        private final boolean available;

        private RuntimeModel(String version, boolean available)
        {
            this.version = version;
            this.available = available;
        }
    }

    private Map<String, Object> withTrustedAttachment(Map<String, Object> source) throws IOException
    {
        if (source == null)
        {
            throw new IOException("请求体不能为空");
        }
        Long attachmentId = toLong(source.get("attachmentId"), null);
        PhmAttachmentEntity attachment = attachmentStorageService.getAccessibleDiagnosisInput(attachmentId);
        if (attachment == null)
        {
            throw new IOException("attachmentId 无效或无权访问");
        }
        String deviceCode = stringValue(source.get("deviceCode"), null);
        PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
        query.setDeviceCode(deviceCode);
        PhmDeviceEntity device = dataScopeService.getDevice(query);
        if (device == null || (attachment.getBizId() != null && !attachment.getBizId().equals(device.getId())))
        {
            throw new IOException("诊断输入附件与授权设备不匹配");
        }
        Map<String, Object> payload = new LinkedHashMap<>(source);
        payload.remove("filePath");
        payload.remove("modelArtifactUri");
        payload.remove("modelArtifactSha256");
        String modelType = stringValue(source.get("modelType"), defaultModelType);
        ResolvedModel resolvedModel = resolveModel(modelType,
            stringValue(source.get("modelVersion"), null));
        PhmMeasurePointEntity point = resolveDiagnosisPoint(device,
            toLong(source.get("pointId"), null));
        payload.put("attachmentId", attachment.getId());
        payload.put("filePath", attachmentStorageService.trustedContentPath(attachment).toString());
        payload.put("filename", attachment.getFileName());
        payload.put("deviceCode", device.getDeviceCode());
        payload.put("modelType", modelType);
        putResolvedModel(payload, resolvedModel);
        if (point != null)
        {
            payload.put("pointId", point.getId());
            payload.put("channelId", point.getChannelId());
        }
        return payload;
    }

    private boolean taskBelongsTo(InferenceTaskEntity task, String deviceCode, Long attachmentId)
    {
        return task != null
            && deviceCode != null
            && deviceCode.equals(task.getDeviceCode())
            && attachmentId != null
            && String.valueOf(attachmentId).equals(task.getInputRef());
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/inference/health")
    public AjaxResult inferenceHealth(@RequestParam(defaultValue = "gear") String modelType)
    {
        try
        {
            Map<String, Object> response = proxyGet(modelType, "/internal/health/ready");
            return AjaxResult.success(extractPythonData(response));
        }
        catch (Exception ex)
        {
            return AjaxResult.error("推理服务不可用: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/inference/files")
    public AjaxResult inferenceFiles(@RequestParam(defaultValue = "gear") String modelType,
        @RequestParam(required = false) String deviceCode,
        @RequestParam(required = false) Long pointId)
    {
        List<PhmAttachmentEntity> inputs;
        if (hasText(deviceCode))
        {
            PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
            query.setDeviceCode(deviceCode.trim());
            com.ruoyi.sensor.domain.entity.PhmDeviceEntity device = dataScopeService.getDevice(query);
            if (device == null)
            {
                inputs = List.of();
            }
            else if (pointId != null)
            {
                PhmMeasurePointEntity point = resolveDiagnosisPoint(device, pointId);
                inputs = attachmentStorageService.listAccessibleDiagnosisInputsForPoint(device.getId(), point.getId());
            }
            else
            {
                inputs = attachmentStorageService.listAccessibleDiagnosisInputsForDevice(device.getId());
            }
        }
        else
        {
            inputs = attachmentStorageService.listAccessibleDiagnosisInputs();
        }
        List<Map<String, Object>> files = inputs.stream()
            .map(item -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", item.getId());
                result.put("name", item.getFileName());
                result.put("label", item.getFileName());
                result.put("source_name", item.getFileName());
                result.put("sha256", item.getSha256());
                result.put("createdAt", item.getCreateTime());
                result.put("fileSize", item.getFileSize());
                result.put("pointId", item.getPointId());
                result.put("channelId", item.getChannelId());
                result.put("sourceType", item.getRemark() != null
                    && item.getRemark().contains("SOURCE:MAT_TCP") ? "MAT_TCP" : "BROWSER_UPLOAD");
                result.put("modelType", modelType);
                return result;
            })
            .collect(java.util.stream.Collectors.toList());
        return AjaxResult.success(files);
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:run')")
    @GetMapping("/inference/analyze")
    public AjaxResult inferenceAnalyze(@RequestParam(required = false) Long attachmentId,
        @RequestParam(defaultValue = "gear") String modelType,
        @RequestParam(required = false) String modelVersion,
        @RequestParam String deviceCode,
        @RequestParam(required = false) Integer channelId,
        @RequestParam(required = false) Long pointId)
    {
        try
        {
            PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
            query.setDeviceCode(deviceCode);
            PhmDeviceEntity device = dataScopeService.getDevice(query);
            if (device == null)
            {
                return AjaxResult.error("无权访问指定设备");
            }
            PhmMeasurePointEntity point = resolveDiagnosisPoint(device, pointId);
            ResolvedModel resolvedModel = resolveModel(modelType, modelVersion);
            PhmAttachmentEntity attachment = attachmentId == null
                ? attachmentStorageService.listAccessibleDiagnosisInputsForDevice(device.getId())
                    .stream().findFirst().orElse(null)
                : attachmentStorageService.getAccessibleDiagnosisInput(attachmentId);
            if (attachment == null)
            {
                return AjaxResult.error("暂无可分析的诊断输入附件");
            }
            if (attachment.getBizId() == null || !attachment.getBizId().equals(device.getId()))
            {
                return AjaxResult.error("诊断输入附件与授权设备不匹配");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("attachmentId", attachment.getId());
            payload.put("filePath", attachmentStorageService.trustedContentPath(attachment).toString());
            payload.put("filename", attachment.getFileName());
            payload.put("deviceCode", deviceCode);
            payload.put("channelId", point == null ? channelId : point.getChannelId());
            payload.put("pointId", point == null ? null : point.getId());
            payload.put("modelType", modelType);
            putResolvedModel(payload, resolvedModel);
            payload.put("taskId", UUID.randomUUID().toString());
            payload.put("requestId", UUID.randomUUID().toString());
            payload.put("sampleTime", DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, new Date()));
            Map<String, Object> response = callPythonInfer(payload);
            validatePythonResult(response);
            validateRequestedModel(response, modelType, resolvedModel.modelVersion);
            Map<String, Object> normalized = normalizePythonResult(response, deviceCode,
                attachment.getFileName(), payload);
            phmService.syncDiagnosisResult(normalized);
            phmService.recalculateDiagnosisState(deviceCode);
            pushDiagnosis(normalized);
            return AjaxResult.success(extractPythonData(response));
        }
        catch (Exception ex)
        {
            return AjaxResult.error("诊断分析失败: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:run')")
    @PostMapping(value = "/inference/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult inferenceUpload(@RequestParam("file") MultipartFile file,
        @RequestParam(defaultValue = "gear", name = "model_type") String modelType,
        @RequestParam(required = false, name = "model_version") String modelVersion,
        @RequestParam(name = "device_code") String deviceCode,
        @RequestParam(required = false, name = "channel_id") Integer channelId,
        @RequestParam(name = "point_id") Long pointId)
    {
        try
        {
            PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
            query.setDeviceCode(deviceCode);
            PhmDeviceEntity device = dataScopeService.getDevice(query);
            if (device == null)
            {
                return AjaxResult.error("无权访问指定设备");
            }
            PhmMeasurePointEntity point = resolveDiagnosisPoint(device, pointId);
            PhmAttachmentEntity attachment = attachmentStorageService.storeDiagnosisInput(file, device.getId(),
                point.getId(), point.getChannelId(), modelType, SecurityUtils.getUsername());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("attachmentId", attachment.getId());
            result.put("filename", attachment.getFileName());
            result.put("fileSize", attachment.getFileSize());
            result.put("sha256", attachment.getSha256());
            result.put("sourceType", "BROWSER_UPLOAD");
            result.put("deviceCode", deviceCode);
            result.put("channelId", point.getChannelId());
            result.put("pointId", point.getId());
            return AjaxResult.success(result);
        }
        catch (Exception ex)
        {
            return AjaxResult.error("上传诊断失败: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasAnyPermi('sensor:history:list,sensor:diagnosis:view')")
    @GetMapping("/inference/history")
    public AjaxResult inferenceHistory(@RequestParam(required = false, name = "start_time") String startTime,
        @RequestParam(required = false, name = "end_time") String endTime,
        @RequestParam(required = false, name = "device_code") String deviceCode,
        @RequestParam(required = false, name = "point_id") Long pointId,
        @RequestParam(required = false, name = "sourceType") String sourceType)
    {
        PhmService.DateRange range = new PhmService.DateRange(
            startTime == null ? null : DateUtils.parseDate(startTime),
            endTime == null ? null : DateUtils.parseDate(endTime));
        List<EnhancedInferenceRecordEntity> rows = phmService.listDiagnosisHistory(range, deviceCode, pointId);
        java.util.Set<String> accessibleCodes = dataScopeService.listDevices(new PhmDeviceScopeQuery()).stream()
            .map(com.ruoyi.sensor.domain.entity.PhmDeviceEntity::getDeviceCode)
            .collect(java.util.stream.Collectors.toSet());
        return AjaxResult.success(rows.stream()
            .filter(item -> accessibleCodes.contains(item.getDeviceCode()))
            .filter(item -> sourceType == null || sourceType.isBlank()
                || sourceType.equalsIgnoreCase(item.getSourceType()))
            .collect(java.util.stream.Collectors.toList()));
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/analysis/timeseries")
    public AjaxResult timeseries(@RequestParam(required = false) String deviceCode,
        @RequestParam(defaultValue = "1") Integer channelId,
        @RequestParam(defaultValue = "120") Integer timeLimit,
        @RequestParam(defaultValue = "64") Integer fftLimit)
    {
        Map<String, Object> data = timeSeriesAnalysisService.loadDiagnosisData(normalizeDeviceCode(deviceCode), channelId, timeLimit, fftLimit);
        return AjaxResult.success(data);
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/diagnosis/latest")
    public AjaxResult latest(@RequestParam(required = false) String deviceCode,
        @RequestParam(defaultValue = "1") Integer channelId)
    {
        deviceCode = normalizeDeviceCode(deviceCode);
        EnhancedInferenceRecordEntity record = phmService.getLatestDiagnosis(deviceCode);
        Map<String, Object> frame = timeSeriesAnalysisService.loadDiagnosisData(
            deviceCode, channelId, 120, 64);
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("deviceCode", deviceCode);
        latest.put("channelId", channelId);
        latest.put("dataStatus", record == null ? "no_data" : "available");
        latest.put("modelVersion", record == null ? null : modelVersionFromRemark(record.getRemark()));
        latest.put("diagnosisResult", record == null ? null : record.getDiagnosisResult());
        latest.put("diagnosisName", record == null ? null : record.getDiagnosisResult());
        latest.put("diagnosisDetail", record == null ? null : record.getDiagnosisDetail());
        latest.put("confidence", record == null ? null : record.getConfidence());
        latest.put("healthIndex", record == null ? null : record.getHealthIndex());
        latest.put("riskLevel", record == null ? null : record.getRiskLevel());
        latest.put("alarmLevel", record == null ? null : record.getAlarmLevel());
        latest.put("status", record == null ? "暂无数据" : "完成");
        latest.put("sampleTime", record == null ? null : record.getSampleTime());
        latest.put("latestRms", record == null ? null : record.getRms());
        latest.put("latestPeak", record == null ? null : record.getPeak());
        latest.put("batchId", record == null ? null : record.getBatchId());
        latest.put("waveform", frame.getOrDefault("waveform", new ArrayList<>()));
        latest.put("frequencyAxis", frame.getOrDefault("frequencyAxis", new ArrayList<>()));
        latest.put("spectrum", frame.getOrDefault("spectrum", new ArrayList<>()));
        latest.put("timeseriesRef", record == null ? null : record.getTimeseriesRef());
        latest.put("timeseriesDataStatus", frame.getOrDefault("dataStatus", "no_data"));
        latest.put("evidence", record == null ? new ArrayList<>() : parseJsonList(record.getEvidence()));
        return AjaxResult.success(latest);
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/device/list")
    public AjaxResult deviceList()
    {
        return AjaxResult.success(phmService.listDevices(null));
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/diagnosis/trend")
    public AjaxResult trend(@RequestParam(required = false) String deviceCode)
    {
        deviceCode = normalizeDeviceCode(deviceCode);
        List<EnhancedInferenceRecordEntity> records = phmService.listDiagnosisHistory(
            new PhmService.DateRange(new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000), new Date()),
            deviceCode);
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("dataStatus", records.isEmpty() ? "no_data" : "available");
        List<String> xAxis = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--)
        {
            EnhancedInferenceRecordEntity item = records.get(i);
            if (item.getHealthIndex() != null)
            {
                xAxis.add(DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS,
                    item.getSampleTime() == null ? item.getCreateTime() : item.getSampleTime()));
                values.add(item.getHealthIndex());
            }
        }
        trend.put("xAxis", xAxis);
        trend.put("values", values);
        return AjaxResult.success(trend);
    }

    private Map<String, Object> evidence(String title, String desc, String level)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", title);
        item.put("desc", desc);
        item.put("level", level);
        item.put("type", "高".equals(level) ? "danger" : "warning");
        return item;
    }

    private Map<String, Object> callPythonInfer(Map<String, Object> payload) throws IOException
    {
        // 根据模型类型路由到对应的推理服务端口
        String modelType = payload != null ? String.valueOf(payload.getOrDefault("modelType",
            payload.getOrDefault("model_type", defaultModelType))) : defaultModelType;
        String inferUrl = "bearing".equalsIgnoreCase(modelType) ? bearingInferUrl : gearInferUrl;
        if (inferUrl == null || inferUrl.isBlank())
        {
            throw new IOException("未配置 " + modelType + " 推理服务地址");
        }

        Map<String, Object> inferPayload = new LinkedHashMap<>(payload == null ? new LinkedHashMap<>() : payload);
        // 推理模型由客户端显式选择；缺省时仅作为兼容回退使用齿轮模型。
        if (!inferPayload.containsKey("modelType") && !inferPayload.containsKey("model_type")) {
            inferPayload.put("modelType", defaultModelType);
        }
        String requestId = stringValue(inferPayload.get("requestId"), UUID.randomUUID().toString());
        inferPayload.put("requestId", requestId);
        inferPayload.putIfAbsent("taskId", requestId);
        inferPayload.putIfAbsent("deviceCode", resolveDeviceCode(inferPayload));
        inferPayload.putIfAbsent("sampleTime", DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, new Date()));
        String body = JSON.toJSONString(inferPayload);

        String response = restClient.post()
            .uri(inferUrl)
            .header("X-Internal-Token", internalToken)
            .header("X-Request-Id", requestId)
            .header("X-Task-Id", stringValue(inferPayload.get("taskId"), requestId))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus(status -> status.value() >= 400, (req, resp) -> {
                throw new IOException("Python 推理服务返回 HTTP " + resp.getStatusCode());
            })
            .body(String.class);

        return JSON.parseObject(response, Map.class);
    }

    private Map<String, Object> proxyGet(String modelType, String path) throws IOException
    {
        String response = restClient.get()
            .uri(inferenceBaseUrl(modelType) + path)
            .header("X-Internal-Token", internalToken)
            .header("X-Request-Id", UUID.randomUUID().toString())
            .retrieve()
            .onStatus(status -> status.value() >= 400, (req, resp) -> {
                throw new IOException("内部推理服务返回 HTTP " + resp.getStatusCode());
            })
            .body(String.class);
        return JSON.parseObject(response, Map.class);
    }

    private String inferenceBaseUrl(String modelType) throws IOException
    {
        String configured = "bearing".equalsIgnoreCase(modelType) ? bearingInferUrl : gearInferUrl;
        if (configured == null || configured.isBlank())
        {
            throw new IOException("未配置内部推理服务地址");
        }
        String base = configured.trim();
        if (base.endsWith("/internal/infer"))
        {
            return base.substring(0, base.length() - "/internal/infer".length());
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @SuppressWarnings("unchecked")
    private Object extractPythonData(Map<String, Object> response)
    {
        return response != null && response.get("data") instanceof Map
            ? response.get("data")
            : response != null && response.containsKey("data") ? response.get("data") : response;
    }

    @SuppressWarnings("unchecked")
    private void validatePythonResult(Map<String, Object> pythonResult) throws IOException
    {
        if (pythonResult == null || pythonResult.isEmpty())
        {
            throw new IOException("推理服务返回空结果");
        }
        if (Boolean.FALSE.equals(pythonResult.get("success")))
        {
            throw new IOException("推理服务返回失败状态: " + stringValue(pythonResult.get("message"), "unknown"));
        }
        Map<String, Object> result = pythonResult.get("data") instanceof Map
            ? (Map<String, Object>) pythonResult.get("data") : pythonResult;
        requireResultText(result, "taskId");
        requireResultText(result, "deviceCode");
        requireResultText(result, "modelType");
        requireResultText(result, "modelVersion");
        requireResultText(result, "sampleTime");
        requireAnyResultText(result, "diagnosisResult", "diagnosisName", "label");
        requireNumberInRange(result, "confidence", 0, 100);
        requireNumberInRange(result, "healthIndex", 0, 100);
        String risk = requireResultText(result, "riskLevel");
        if (!Arrays.asList("低", "中", "高", "low", "medium", "high").contains(risk))
        {
            throw new IOException("推理结果 riskLevel 非法");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizePythonResult(Map<String, Object> pythonResult, String deviceCode, String filePath,
        Map<String, Object> payload)
    {
        Map<String, Object> data = pythonResult == null ? new LinkedHashMap<>() : pythonResult;
        // Python 服务返回 {"success": true, "data": {...}}
        Map<String, Object> nested = data.containsKey("data") && data.get("data") instanceof Map ? (Map<String, Object>) data.get("data") : data;

        // 诊断结果：Python返回 diagnosisResult / label / diagnosisName
        String diagResult = firstRequiredText(nested, "diagnosisResult", "diagnosisName", "label");
        String diagDetail = firstOptionalText(nested, "diagnosisDetail", "diagnosis_detail");
        String modelVersion = firstRequiredText(nested, "modelVersion", "model_version");
        String modelType = firstRequiredText(nested, "modelType", "model_type");

        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("deviceCode", deviceCode);
        latest.put("taskId", nested.get("taskId"));
        latest.put("requestId", nested.get("requestId"));
        latest.put("pointId", payload == null ? null : payload.get("pointId"));
        latest.put("channelId", payload == null ? null : payload.get("channelId"));
        latest.put("sampleTime", nested.get("sampleTime"));
        latest.put("modelVersion", modelVersion);
        latest.put("modelType", modelType);
        latest.put("diagnosisResult", diagResult);
        latest.put("diagnosisName", diagResult);
        latest.put("diagnosisDetail", diagDetail);
        // Python 返回 confidence(百分比) / healthIndex / riskLevel / alarmLevel
        latest.put("confidence", requiredNumber(nested, "confidence"));
        latest.put("healthIndex", requiredNumber(nested, "healthIndex"));
        latest.put("riskLevel", firstRequiredText(nested, "riskLevel", "risk_level"));
        latest.put("alarmLevel", firstOptionalText(nested, "alarmLevel", "alarm_level"));
        latest.put("sampleRate", optionalNumber(nested, "sampleRate", "sample_rate"));
        latest.put("closedPrediction", firstOptionalText(nested, "closedPrediction", "closed_prediction"));
        latest.put("decisionReason", firstOptionalText(nested, "decisionReason", "decision_reason"));
        latest.put("unknownRatio", optionalNumber(nested, "unknownRatio", "unknown_ratio"));
        latest.put("segmentConsistency", optionalNumber(nested, "segmentConsistency", "segment_consistency"));
        latest.put("meanMahalanobis", optionalNumber(nested, "meanMahalanobis", "mean_mahalanobis"));
        latest.put("meanEntropy", optionalNumber(nested, "meanEntropy", "mean_entropy"));
        Object topProbabilities = nested.containsKey("topProbabilities")
            ? nested.get("topProbabilities") : nested.get("top_probabilities");
        latest.put("topProbabilities", topProbabilities instanceof List
            ? topProbabilities : new ArrayList<>());
        latest.put("status", "SUCCEEDED");
        latest.put("resultStatus", "VALID");
        latest.put("latestRms", optionalNumber(nested, "rms", "latestRms"));
        latest.put("latestPeak", optionalNumber(nested, "peak", "latestPeak"));
        latest.put("batchId", payload == null ? null : payload.get("batchId"));
        latest.put("filePath", filePath);
        // 波形/频谱：Python 返回 time_data / freq_axis / freq_data
        latest.put("waveform", nested.getOrDefault("time_data",
            nested.getOrDefault("waveform", new ArrayList<>())));
        latest.put("frequencyAxis", nested.getOrDefault("freq_axis",
            nested.getOrDefault("frequencyAxis", new ArrayList<>())));
        latest.put("spectrum", nested.getOrDefault("freq_data",
            nested.getOrDefault("spectrum", new ArrayList<>())));

        // 诊断证据：优先使用 Python 返回的 evidence 列表
        Object evidenceObj = nested.get("evidence");
        if (evidenceObj instanceof List) {
            latest.put("evidence", evidenceObj);
        } else {
            latest.put("evidence", new ArrayList<>());
        }
        return latest;
    }

    private Map<String, Object> buildFailureResult(String deviceCode, String filePath, String reason)
    {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("deviceCode", deviceCode);
        failure.put("sampleTime", new Date());
        failure.put("modelVersion", null);
        failure.put("diagnosisResult", "任务失败");
        failure.put("diagnosisName", "任务失败");
        failure.put("diagnosisDetail", reason == null ? "推理服务不可用" : reason);
        failure.put("confidence", null);
        failure.put("healthIndex", null);
        failure.put("riskLevel", null);
        failure.put("status", "failed");
        failure.put("latestRms", null);
        failure.put("latestPeak", null);
        failure.put("filePath", filePath);
        failure.put("waveform", new ArrayList<>());
        failure.put("frequencyAxis", new ArrayList<>());
        failure.put("spectrum", new ArrayList<>());
        failure.put("evidence", new ArrayList<>());
        return failure;
    }

    private String stringValue(Object value, String defaultValue)
    {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String requireResultText(Map<String, Object> result, String key) throws IOException
    {
        Object value = result.get(key);
        if (value == null || String.valueOf(value).isBlank())
        {
            throw new IOException("推理结果缺少 " + key);
        }
        return String.valueOf(value).trim();
    }

    private String requireAnyResultText(Map<String, Object> result, String... keys) throws IOException
    {
        for (String key : keys)
        {
            Object value = result.get(key);
            if (value != null && !String.valueOf(value).isBlank())
            {
                return String.valueOf(value).trim();
            }
        }
        throw new IOException("推理结果缺少 " + String.join("/", keys));
    }

    private double requireNumberInRange(Map<String, Object> result, String key, double min, double max)
        throws IOException
    {
        Object value = result.get(key);
        double number;
        try
        {
            number = value instanceof Number ? ((Number) value).doubleValue()
                : Double.parseDouble(String.valueOf(value));
        }
        catch (Exception ex)
        {
            throw new IOException("推理结果缺少或无法解析 " + key, ex);
        }
        if (!Double.isFinite(number) || number < min || number > max)
        {
            throw new IOException("推理结果 " + key + " 超出范围");
        }
        return number;
    }

    private String firstRequiredText(Map<String, Object> data, String... keys)
    {
        for (String key : keys)
        {
            Object value = data.get(key);
            if (value != null && !String.valueOf(value).isBlank())
            {
                return String.valueOf(value).trim();
            }
        }
        throw new IllegalArgumentException("缺少必填推理字段: " + String.join("/", keys));
    }

    private String firstOptionalText(Map<String, Object> data, String... keys)
    {
        for (String key : keys)
        {
            Object value = data.get(key);
            if (value != null && !String.valueOf(value).isBlank())
            {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private double requiredNumber(Map<String, Object> data, String key)
    {
        Object value = data.get(key);
        if (value instanceof Number)
        {
            return ((Number) value).doubleValue();
        }
        if (value != null)
        {
            return Double.parseDouble(String.valueOf(value));
        }
        throw new IllegalArgumentException("缺少必填推理字段: " + key);
    }

    private Double optionalNumber(Map<String, Object> data, String... keys)
    {
        for (String key : keys)
        {
            Object value = data.get(key);
            if (value instanceof Number)
            {
                return ((Number) value).doubleValue();
            }
            if (value != null && !String.valueOf(value).isBlank())
            {
                return Double.parseDouble(String.valueOf(value));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> parseJsonList(String json)
    {
        if (json == null || json.isBlank())
        {
            return new ArrayList<>();
        }
        try
        {
            return JSON.parseObject(json, List.class);
        }
        catch (Exception ignored)
        {
            return new ArrayList<>();
        }
    }

    private String modelVersionFromRemark(String remark)
    {
        if (remark == null || remark.isBlank())
        {
            return null;
        }
        try
        {
            Map<String, Object> metadata = JSON.parseObject(remark, Map.class);
            return metadata == null ? null : stringValue(metadata.get("modelVersion"), null);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private double toNumber(Object value, double defaultValue)
    {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private void pushDiagnosis(Map<String, Object> diagnosis)
    {
        ChannelRealtimeVo vo = new ChannelRealtimeVo();
        vo.setDeviceCode(stringValue(diagnosis.get("deviceCode"), defaultDeviceCode));
        vo.setChannelId((int) toNumber(diagnosis.get("channelId"), 1));
        vo.setSampleTime(java.time.LocalDateTime.now());
        vo.setRms(toNumber(diagnosis.get("latestRms"), 0));
        vo.setPeak(toNumber(diagnosis.get("latestPeak"), 0));
        vo.setAlarm("高".equals(stringValue(diagnosis.get("riskLevel"), "低")));
        vo.setAlarmMessage(stringValue(diagnosis.get("diagnosisDetail"), ""));
        boolean failed = "failed".equals(resolveResultState(stringValue(diagnosis.get("status"), "")));
        if (!failed)
        {
            webSocketPushService.pushFeature(vo);
        }

        SensorWebSocketMessageVo message = new SensorWebSocketMessageVo();
        message.setType(WS_EVENT_ANALYSIS);
        message.setEvent(WS_EVENT_ANALYSIS);
        message.setDeviceCode(vo.getDeviceCode());
        message.setChannelId(vo.getChannelId());
        message.setBatchId(stringValue(diagnosis.get("batchId"), null));
        message.setStatus(stringValue(diagnosis.get("status"), "完成"));
        message.setResultState(resolveResultState(message.getStatus()));
        message.setDiagnosisResult(stringValue(diagnosis.get("diagnosisResult"), stringValue(diagnosis.get("diagnosisName"), "正常")));
        message.setDiagnosisName(stringValue(diagnosis.get("diagnosisName"), stringValue(diagnosis.get("diagnosisResult"), "正常")));
        message.setDiagnosisDetail(stringValue(diagnosis.get("diagnosisDetail"), ""));
        message.setModelType(stringValue(diagnosis.get("modelType"), defaultModelType));
        message.setConfidence(toNumber(diagnosis.get("confidence"), 0));
        message.setHealthIndex(toNumber(diagnosis.get("healthIndex"), 0));
        message.setRiskLevel(stringValue(diagnosis.get("riskLevel"), null));
        message.setRms(vo.getRms());
        message.setPeak(vo.getPeak());
        message.setSampleTime(java.time.LocalDateTime.now());
        message.setWaveform(toDoubleList(diagnosis.get("waveform")));
        message.setFrequencyAxis(toDoubleList(diagnosis.get("frequencyAxis")));
        message.setSpectrum(toDoubleList(diagnosis.get("spectrum")));
        message.setEvidence(toEvidenceList(diagnosis.get("evidence")));
        message.setMessage(message.getDiagnosisDetail());
        com.ruoyi.sensor.websocket.SensorWebSocketHandler.broadcastDiagnosis(message);
    }

    private Long toLong(Object value, Long defaultValue)
    {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Double> toDoubleList(Object value)
    {
        List<Double> list = new ArrayList<>();
        if (value == null) {
            return list;
        }
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                list.add(toNumber(item, 0));
            }
            return list;
        }
        if (value.getClass().isArray()) {
            Object[] arr = (Object[]) value;
            for (Object item : arr) {
                list.add(toNumber(item, 0));
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toEvidenceList(Object value)
    {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return new ArrayList<>();
    }

    private String resolveResultState(String status)
    {
        String normalized = status == null ? "" : status.trim();
        if (normalized.contains("分析中") || normalized.contains("running") || normalized.contains("pending")) {
            return "running";
        }
        if (normalized.contains("失败") || normalized.contains("error") || normalized.contains("failed")) {
            return "failed";
        }
        if (normalized.contains("完成") || normalized.contains("done") || normalized.contains("success")) {
            return "done";
        }
        return "idle";
    }

    private String resolveDeviceCode(Map<String, Object> payload)
    {
        Object value = payload == null ? null : payload.get("deviceCode");
        return normalizeDeviceCode(value == null ? null : String.valueOf(value));
    }

    private String normalizeDeviceCode(String value)
    {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? defaultDeviceCode : normalized;
    }
}
