package com.ruoyi.sensor.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
import com.ruoyi.sensor.service.VibrationAnalysisBatchService;
import com.ruoyi.sensor.service.VibrationAnalysisPersistenceService;
import com.ruoyi.sensor.tdengine.TdengineQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sensor/vibration")
public class VibrationDiagnosisController
{
    private static final String PYTHON_INFER_URL = "http://127.0.0.1:5000/infer";
    private static final String WS_EVENT_ANALYSIS = "analysis";
    private static final String WS_EVENT_REALTIME = "realtime";

    private final TdengineQueryService tdengineQueryService;
    private final VibrationAnalysisBatchService batchService;
    private final VibrationAnalysisPersistenceService persistenceService;
    private final SensorWebSocketPushService webSocketPushService;

    public VibrationDiagnosisController(TdengineQueryService tdengineQueryService,
        VibrationAnalysisBatchService batchService,
        VibrationAnalysisPersistenceService persistenceService,
        SensorWebSocketPushService webSocketPushService)
    {
        this.tdengineQueryService = tdengineQueryService;
        this.batchService = batchService;
        this.persistenceService = persistenceService;
        this.webSocketPushService = webSocketPushService;
    }

    @PostMapping("/receiver/callback")
    public AjaxResult receiverCallback(@RequestBody Map<String, Object> payload)
    {
        String filePath = payload == null ? null : String.valueOf(payload.get("filePath"));
        String filename = payload == null ? null : String.valueOf(payload.get("filename"));
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("deviceCode", payload == null ? "BEARING-001" : String.valueOf(payload.getOrDefault("deviceCode", "BEARING-001")));
        latest.put("sampleTime", new Date());
        latest.put("modelVersion", "best_model_classwise_maha.pth");
        latest.put("diagnosisResult", "在线诊断任务已接收");
        latest.put("diagnosisName", "在线诊断任务已接收");
        latest.put("diagnosisDetail", "MAT 文件已接收，等待模型推理服务处理");
        latest.put("confidence", 0);
        latest.put("healthIndex", 100);
        latest.put("riskLevel", "低");
        latest.put("status", "分析中");
        latest.put("latestRms", 0);
        latest.put("latestPeak", 0);
        latest.put("batchId", 1L);
        latest.put("filePath", filePath);
        latest.put("filename", filename);
        latest.put("waveform", new ArrayList<>());
        latest.put("frequencyAxis", new ArrayList<>());
        latest.put("spectrum", new ArrayList<>());
        latest.put("evidence", Arrays.asList(
            evidence("文件已接收", "服务端已收到 MAT 文件并完成持久化。", "中"),
            evidence("推理待执行", "后续可在此处接入 Python 推理服务与模型结果。", "中")
        ));
        return AjaxResult.success(latest);
    }

    @PostMapping("/receiver/analyze")
    public AjaxResult receiverAnalyze(@RequestBody Map<String, Object> payload)
    {
        String deviceCode = payload == null ? "BEARING-001" : String.valueOf(payload.getOrDefault("deviceCode", "BEARING-001"));
        String filePath = payload == null ? null : String.valueOf(payload.get("filePath"));

        try {
            Map<String, Object> pythonResult = callPythonInfer(payload);
            Map<String, Object> normalized = normalizePythonResult(pythonResult, deviceCode, filePath, payload);
            persistDiagnosis(normalized);
            pushDiagnosis(normalized);
            return AjaxResult.success(normalized);
        } catch (Exception ex) {
            Map<String, Object> fallback = buildFallbackDiagnosis(deviceCode, filePath, ex.getMessage());
            persistDiagnosis(fallback);
            pushDiagnosis(fallback);
            return AjaxResult.error("推理失败: " + ex.getMessage()).put("data", fallback);
        }
    }

    @GetMapping("/analysis/tdengine")
    public AjaxResult tdengine(@RequestParam(defaultValue = "BEARING-001") String deviceCode,
        @RequestParam(defaultValue = "1") Integer channelId,
        @RequestParam(defaultValue = "120") Integer timeLimit,
        @RequestParam(defaultValue = "64") Integer fftLimit)
    {
        Map<String, Object> data = tdengineQueryService.loadDiagnosisData(deviceCode, channelId, timeLimit, fftLimit);
        return AjaxResult.success(data);
    }

    @GetMapping("/diagnosis/latest")
    public AjaxResult latest(@RequestParam(defaultValue = "BEARING-001") String deviceCode)
    {
        Map<String, Object> data = tdengineQueryService.loadDiagnosisData(deviceCode, 1, 120, 64);
        Map<String, Object> latest = new LinkedHashMap<>(data);
        latest.put("deviceCode", deviceCode);
        latest.put("sampleTime", new Date());
        latest.put("modelVersion", "best_model_classwise_maha.pth");
        latest.put("diagnosisResult", data.getOrDefault("diagnosis", "正常").toString());
        latest.put("diagnosisName", data.getOrDefault("diagnosis", "正常").toString());
        latest.put("diagnosisDetail", data.getOrDefault("diagnosisDetail", "基于当前特征完成在线诊断").toString());
        latest.put("confidence", data.getOrDefault("confidence", 82));
        latest.put("healthIndex", Math.max(0, 100 - ((Number) data.getOrDefault("rms", 0)).intValue() * 5));
        latest.put("riskLevel", ((Number) data.getOrDefault("rms", 0)).doubleValue() > 7 ? "高" : "中");
        latest.put("status", "完成");
        latest.put("latestRms", data.getOrDefault("rms", 0));
        latest.put("latestPeak", data.getOrDefault("peak", 0));
        latest.put("batchId", 1L);
        latest.put("waveform", data.getOrDefault("waveform", new ArrayList<>()));
        latest.put("frequencyAxis", data.getOrDefault("frequencyAxis", new ArrayList<>()));
        latest.put("spectrum", data.getOrDefault("spectrum", new ArrayList<>()));
        latest.put("evidence", Arrays.asList(
            evidence("频域能量变化", "检测到中高频能量抬升，符合轴承冲击类故障特征。", "中"),
            evidence("RMS 波动", "RMS 相比平稳阶段有所抬升，需关注设备运行状态。", "中"),
            evidence("模型输出", "当前模型置信度达到阈值，可作为在线辅助诊断依据。", "高")
        ));
        return AjaxResult.success(latest);
    }

    @GetMapping("/device/list")
    public AjaxResult deviceList()
    {
        List<VibrationAnalysisBatchEntity> batches = batchService.list(new VibrationAnalysisBatchEntity());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (batches != null && !batches.isEmpty()) {
            for (VibrationAnalysisBatchEntity batch : batches) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("deviceCode", batch.getDeviceCode());
                row.put("deviceName", batch.getDeviceCode());
                row.put("vibrationValue", batch.getSampleRate());
                row.put("statusText", batch.getSampleRate() != null && batch.getSampleRate() > 7 ? "异常" : "正常");
                row.put("status", batch.getSampleRate() != null && batch.getSampleRate() > 7 ? "danger" : "success");
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            rows.add(device("BEARING-001", "1#主电机", 6.4, "预警", "warning"));
            rows.add(device("BEARING-002", "2#风机", 8.8, "异常", "danger"));
            rows.add(device("BEARING-003", "3#泵组", 3.2, "正常", "success"));
            rows.add(device("BEARING-004", "4#减速箱", 4.9, "正常", "success"));
        }
        return AjaxResult.success(rows);
    }

    @GetMapping("/diagnosis/trend")
    public AjaxResult trend(@RequestParam(defaultValue = "BEARING-001") String deviceCode)
    {
        Map<String, Object> data = tdengineQueryService.loadDiagnosisData(deviceCode, 1, 120, 64);
        Map<String, Object> trend = new LinkedHashMap<>();
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
        HttpURLConnection conn = (HttpURLConnection) new URL(PYTHON_INFER_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(120000);  // 推理耗时约 6-8s (CPU)，预留充足余量
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        Map<String, Object> inferPayload = new LinkedHashMap<>(payload == null ? new LinkedHashMap<>() : payload);
        // 推理模型由客户端显式选择；缺省时仅作为兼容回退使用齿轮模型。
        if (!inferPayload.containsKey("modelType") && !inferPayload.containsKey("model_type")) {
            inferPayload.put("modelType", "gear");
        }
        String body = JSON.toJSONString(inferPayload);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            status >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        if (status < 200 || status >= 300) {
            throw new IOException("Python 推理服务返回 HTTP " + status + ": " + sb);
        }
        return JSON.parseObject(sb.toString(), Map.class);
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

    private Map<String, Object> buildFallbackDiagnosis(String deviceCode, String filePath, String reason)
    {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("deviceCode", deviceCode);
        fallback.put("sampleTime", new Date());
        fallback.put("modelVersion", "best_model_classwise_maha.pth");
        fallback.put("diagnosisResult", "推理失败");
        fallback.put("diagnosisName", "推理失败");
        fallback.put("diagnosisDetail", reason == null ? "Python 推理服务不可用" : reason);
        fallback.put("confidence", 0);
        fallback.put("healthIndex", 0);
        fallback.put("riskLevel", "高");
        fallback.put("status", "异常");
        fallback.put("latestRms", 0);
        fallback.put("latestPeak", 0);
        fallback.put("filePath", filePath);
        fallback.put("waveform", new ArrayList<>());
        fallback.put("frequencyAxis", new ArrayList<>());
        fallback.put("spectrum", new ArrayList<>());
        fallback.put("evidence", Arrays.asList(evidence("推理异常", fallback.get("diagnosisDetail").toString(), "高")));
        return fallback;
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
        record.setDeviceCode(stringValue(diagnosis.get("deviceCode"), "BEARING-001"));
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
        vo.setDeviceCode(stringValue(diagnosis.get("deviceCode"), "BEARING-001"));
        vo.setChannelId(1);
        vo.setSampleTime(java.time.LocalDateTime.now());
        vo.setRms(toNumber(diagnosis.get("latestRms"), 0));
        vo.setPeak(toNumber(diagnosis.get("latestPeak"), 0));
        vo.setAlarm("高".equals(stringValue(diagnosis.get("riskLevel"), "低")));
        vo.setAlarmMessage(stringValue(diagnosis.get("diagnosisDetail"), ""));
        webSocketPushService.pushFeature(vo);

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
        message.setModelType(stringValue(diagnosis.get("modelType"), "gear"));
        message.setConfidence(toNumber(diagnosis.get("confidence"), 0));
        message.setHealthIndex(toNumber(diagnosis.get("healthIndex"), 0));
        message.setRiskLevel(stringValue(diagnosis.get("riskLevel"), "低"));
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

    private Map<String, Object> device(String deviceCode, String deviceName, double vibrationValue, String statusText, String status)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deviceCode", deviceCode);
        row.put("deviceName", deviceName);
        row.put("vibrationValue", vibrationValue);
        row.put("statusText", statusText);
        row.put("status", status);
        return row;
    }
}
