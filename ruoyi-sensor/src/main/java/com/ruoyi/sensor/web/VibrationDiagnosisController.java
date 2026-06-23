package com.ruoyi.sensor.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.sensor.domain.entity.EnhancedInferenceRecordEntity;
import com.ruoyi.sensor.domain.entity.VibrationAnalysisBatchEntity;
import com.ruoyi.sensor.domain.entity.VibrationAnalysisRecordEntity;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.vo.SensorWebSocketMessageVo;
import com.ruoyi.sensor.service.SensorWebSocketPushService;
import com.ruoyi.sensor.service.PhmService;
import com.ruoyi.sensor.service.VibrationAnalysisBatchService;
import com.ruoyi.sensor.service.VibrationAnalysisPersistenceService;
import com.ruoyi.sensor.service.timeseries.TimeSeriesAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/sensor/diagnosis", "/sensor/vibration"})
public class VibrationDiagnosisController
{
    private static final String WS_EVENT_ANALYSIS = "analysis";
    private static final String WS_EVENT_REALTIME = "realtime";

    private final TimeSeriesAnalysisService timeSeriesAnalysisService;
    private final VibrationAnalysisBatchService batchService;
    private final VibrationAnalysisPersistenceService persistenceService;
    private final SensorWebSocketPushService webSocketPushService;
    private final PhmService phmService;
    private final String gearInferUrl;
    private final String bearingInferUrl;
    private final String internalToken;
    private final String defaultDeviceCode;
    private final String defaultModelType;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final RestClient restClient;

    public VibrationDiagnosisController(TimeSeriesAnalysisService timeSeriesAnalysisService,
        VibrationAnalysisBatchService batchService,
        VibrationAnalysisPersistenceService persistenceService,
        SensorWebSocketPushService webSocketPushService,
        PhmService phmService,
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
        this.persistenceService = persistenceService;
        this.webSocketPushService = webSocketPushService;
        this.phmService = phmService;
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

    @PreAuthorize("hasAuthority('sensor:collector:upload')")
    @PostMapping("/receiver/callback")
    public AjaxResult receiverCallback(@RequestBody Map<String, Object> payload)
    {
        String filePath = payload == null ? null : String.valueOf(payload.get("filePath"));
        String filename = payload == null ? null : String.valueOf(payload.get("filename"));
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("deviceCode", resolveDeviceCode(payload));
        latest.put("channelId", payload == null ? 1 : toNumber(payload.get("channelId"), 1));
        latest.put("sampleTime", new Date());
        latest.put("modelVersion", "best_model_classwise_maha.pth");
        latest.put("diagnosisResult", "在线诊断任务已接收");
        latest.put("diagnosisName", "在线诊断任务已接收");
        latest.put("diagnosisDetail", "MAT 文件已接收，尚未产生模型诊断结果");
        latest.put("confidence", null);
        latest.put("healthIndex", null);
        latest.put("riskLevel", null);
        latest.put("status", "pending");
        latest.put("dataStatus", "pending");
        latest.put("latestRms", null);
        latest.put("latestPeak", null);
        latest.put("batchId", null);
        latest.put("filePath", filePath);
        latest.put("filename", filename);
        latest.put("waveform", new ArrayList<>());
        latest.put("frequencyAxis", new ArrayList<>());
        latest.put("spectrum", new ArrayList<>());
        latest.put("evidence", new ArrayList<>());
        return AjaxResult.success(latest);
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:run')")
    @PostMapping("/receiver/analyze")
    public AjaxResult receiverAnalyze(@RequestBody Map<String, Object> payload)
    {
        String deviceCode = resolveDeviceCode(payload);
        String filePath = payload == null ? null : String.valueOf(payload.get("filePath"));

        try {
            Map<String, Object> pythonResult = callPythonInfer(payload);
            validatePythonResult(pythonResult);
            Map<String, Object> normalized = normalizePythonResult(pythonResult, deviceCode, filePath, payload);
            persistDiagnosis(normalized);
            phmService.syncDiagnosisResult(normalized);
            pushDiagnosis(normalized);
            return AjaxResult.success(normalized);
        } catch (Exception ex) {
            Map<String, Object> failure = buildFailureResult(deviceCode, filePath, ex.getMessage());
            pushDiagnosis(failure);
            return AjaxResult.error("推理失败: " + ex.getMessage()).put("data", failure);
        }
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
    public AjaxResult inferenceFiles(@RequestParam(defaultValue = "gear") String modelType)
    {
        try
        {
            return AjaxResult.success(extractPythonData(proxyGet(modelType, "/internal/files")));
        }
        catch (Exception ex)
        {
            return AjaxResult.error("获取诊断文件失败: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:run')")
    @GetMapping("/inference/analyze")
    public AjaxResult inferenceAnalyze(@RequestParam(required = false) String fileName,
        @RequestParam(defaultValue = "gear") String modelType)
    {
        try
        {
            String path = "/internal/analyze?model_type=" + modelType;
            if (fileName != null && !fileName.isBlank())
            {
                path += "&file_name=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8);
            }
            Map<String, Object> response = proxyGet(modelType, path);
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
        @RequestParam(defaultValue = "gear") String modelType)
    {
        try
        {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("model_type", modelType);
            builder.part("file", new ByteArrayResource(file.getBytes())
            {
                @Override
                public String getFilename()
                {
                    return file.getOriginalFilename();
                }
            }).contentType(MediaType.APPLICATION_OCTET_STREAM);
            String response = restClient.post()
                .uri(inferenceBaseUrl(modelType) + "/internal/analyze/upload")
                .header("X-Internal-Token", internalToken)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(String.class);
            return AjaxResult.success(extractPythonData(JSON.parseObject(response, Map.class)));
        }
        catch (Exception ex)
        {
            return AjaxResult.error("上传诊断失败: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('sensor:diagnosis:view')")
    @GetMapping("/inference/history")
    public AjaxResult inferenceHistory(@RequestParam(required = false, name = "start_time") String startTime,
        @RequestParam(required = false, name = "end_time") String endTime,
        @RequestParam(required = false, name = "device_code") String deviceCode)
    {
        PhmService.DateRange range = new PhmService.DateRange(
            startTime == null ? null : DateUtils.parseDate(startTime),
            endTime == null ? null : DateUtils.parseDate(endTime));
        List<EnhancedInferenceRecordEntity> rows = phmService.listDiagnosisHistory(range, deviceCode);
        return AjaxResult.success(rows);
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
        latest.put("waveform", record == null ? new ArrayList<>() : parseJsonList(record.getWaveJson()));
        latest.put("frequencyAxis", new ArrayList<>());
        latest.put("spectrum", record == null ? new ArrayList<>() : parseJsonList(record.getSpectrumJson()));
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

    private void persistDiagnosis(Map<String, Object> diagnosis)
    {
        VibrationAnalysisRecordEntity record = new VibrationAnalysisRecordEntity();
        record.setBatchId(toLong(diagnosis.get("batchId"), null));
        record.setDeviceCode(firstRequiredText(diagnosis, "deviceCode"));
        record.setRms(optionalNumber(diagnosis, "latestRms", "rms"));
        record.setPeak(optionalNumber(diagnosis, "latestPeak", "peak"));
        record.setDiagnosisResult(firstRequiredText(diagnosis, "diagnosisResult", "diagnosisName"));
        record.setWaveJson(JSON.toJSONString(diagnosis.getOrDefault("waveform", new ArrayList<>())));
        record.setSpectrumJson(JSON.toJSONString(diagnosis.getOrDefault("spectrum", new ArrayList<>())));
        record.setCreateTime(new Date());
        persistenceService.saveAsync(record);
    }

    private void pushDiagnosis(Map<String, Object> diagnosis)
    {
        ChannelRealtimeVo vo = new ChannelRealtimeVo();
        vo.setDeviceCode(stringValue(diagnosis.get("deviceCode"), defaultDeviceCode));
        vo.setChannelId(1);
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
