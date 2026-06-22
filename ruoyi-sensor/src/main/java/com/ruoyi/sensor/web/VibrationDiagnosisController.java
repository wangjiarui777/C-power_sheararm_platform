package com.ruoyi.sensor.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.AjaxResult;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.client.RestClient;

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
            persistDiagnosis(failure);
            pushDiagnosis(failure);
            return AjaxResult.error("推理失败: " + ex.getMessage()).put("data", failure);
        }
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
        Map<String, Object> data = timeSeriesAnalysisService.loadDiagnosisData(deviceCode, channelId, 120, 64);
        Map<String, Object> latest = new LinkedHashMap<>(data);
        boolean available = "available".equals(data.get("dataStatus"));
        double rms = available ? ((Number) data.getOrDefault("rms", 0D)).doubleValue() : 0D;
        latest.put("deviceCode", deviceCode);
        latest.put("channelId", channelId);
        latest.put("modelVersion", null);
        latest.put("diagnosisResult", available ? data.get("diagnosis") : null);
        latest.put("diagnosisName", available ? data.get("diagnosis") : null);
        latest.put("diagnosisDetail", data.get("diagnosisDetail"));
        latest.put("confidence", available ? data.get("confidence") : null);
        latest.put("healthIndex", available ? Math.max(0, 100 - (int) rms * 5) : null);
        latest.put("riskLevel", available ? (rms > 7 ? "高" : rms > 4 ? "中" : "低") : null);
        latest.put("status", available ? "完成" : "暂无数据");
        latest.put("latestRms", available ? data.get("rms") : null);
        latest.put("latestPeak", available ? data.get("peak") : null);
        latest.put("batchId", null);
        latest.put("waveform", data.getOrDefault("waveform", new ArrayList<>()));
        latest.put("frequencyAxis", data.getOrDefault("frequencyAxis", new ArrayList<>()));
        latest.put("spectrum", data.getOrDefault("spectrum", new ArrayList<>()));
        latest.put("evidence", available
                ? Arrays.asList(evidence("时序数据", "诊断结果来自当前设备的已入库波形与频谱。", "中"))
                : new ArrayList<>());
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
        Map<String, Object> data = timeSeriesAnalysisService.loadDiagnosisData(deviceCode, 1, 120, 64);
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("dataStatus", data.get("dataStatus"));
        if (!"available".equals(data.get("dataStatus")))
        {
            trend.put("xAxis", new ArrayList<>());
            trend.put("values", new ArrayList<>());
            return AjaxResult.success(trend);
        }
        trend.put("xAxis", Arrays.asList("D-6", "D-5", "D-4", "D-3", "D-2", "D-1", "Today"));
        trend.put("values", Arrays.asList(72, 74, 71, 68, 65, 62, Math.max(0, 100 - ((Number) data.getOrDefault("rms", 0)).intValue() * 5)));
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
        String body = JSON.toJSONString(inferPayload);

        String response = restClient.post()
            .uri(inferUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus(status -> status.value() >= 400, (req, resp) -> {
                throw new IOException("Python 推理服务返回 HTTP " + resp.getStatusCode());
            })
            .body(String.class);

        return JSON.parseObject(response, Map.class);
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
        boolean hasDiagnosis = result.get("diagnosisResult") != null
            || result.get("diagnosisName") != null
            || result.get("label") != null;
        if (!hasDiagnosis)
        {
            throw new IOException("推理结果缺少诊断标签");
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
        String diagResult = stringValue(nested.get("diagnosisResult"),
            stringValue(nested.get("diagnosisName"),
                stringValue(nested.get("label"), "正常")));
        String diagDetail = stringValue(nested.get("diagnosisDetail"),
            stringValue(nested.get("diagnosis_detail"), "模型推理完成"));
        String modelVersion = stringValue(nested.get("modelVersion"),
            stringValue(nested.get("model_version"), "best_model_classwise_maha.pth"));
        String modelType = stringValue(nested.get("modelType"),
            stringValue(nested.get("model_type"), "gear"));

        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("deviceCode", deviceCode);
        latest.put("sampleTime", new Date());
        latest.put("modelVersion", modelVersion);
        latest.put("modelType", modelType);
        latest.put("diagnosisResult", diagResult);
        latest.put("diagnosisName", diagResult);
        latest.put("diagnosisDetail", diagDetail);
        // Python 返回 confidence(百分比) / healthIndex / riskLevel / alarmLevel
        latest.put("confidence", toNumber(nested.get("confidence"), 0));
        latest.put("healthIndex", toNumber(nested.get("healthIndex"),
            toNumber(nested.get("health_index"), 100)));
        latest.put("riskLevel", stringValue(nested.get("riskLevel"),
            stringValue(nested.get("risk_level"), "低")));
        latest.put("alarmLevel", stringValue(nested.get("alarmLevel"),
            stringValue(nested.get("alarm_level"), "normal")));
        latest.put("status", "完成");
        latest.put("latestRms", toNumber(nested.get("rms"),
            toNumber(nested.get("latestRms"), 0)));
        latest.put("latestPeak", toNumber(nested.get("peak"),
            toNumber(nested.get("latestPeak"), 0)));
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
            latest.put("evidence", Arrays.asList(
                evidence("模型标签", "Python 推理服务返回了实时分类标签 (模型: " + modelType + ")。", "中"),
                evidence("置信度", "模型输出已同步到前端面板。", "中")
            ));
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
        record.setDeviceCode(stringValue(diagnosis.get("deviceCode"), defaultDeviceCode));
        record.setRms(toNumber(diagnosis.get("latestRms"), 0));
        record.setPeak(toNumber(diagnosis.get("latestPeak"), 0));
        record.setDiagnosisResult(stringValue(diagnosis.get("diagnosisResult"), stringValue(diagnosis.get("diagnosisName"), "正常")));
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
