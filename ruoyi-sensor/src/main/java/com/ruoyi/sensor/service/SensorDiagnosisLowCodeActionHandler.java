package com.ruoyi.sensor.service;

import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.lowcode.LowCodeActionContext;
import com.ruoyi.common.lowcode.LowCodeActionHandler;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.sensor.domain.entity.ModelReleaseEntity;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.mapper.ModelReleaseMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Registered low-code action for a synchronous, trusted diagnosis invocation.
 * It deliberately reuses sensor services and never calls a web controller.
 */
@Component
public class SensorDiagnosisLowCodeActionHandler implements LowCodeActionHandler
{
    private static final Set<String> MODEL_TYPES = Set.of("gear", "bearing");
    private static final Set<String> EXECUTABLE = Set.of("ACTIVE", "VALIDATED", "RETIRED");
    private final PhmAttachmentStorageService attachments;
    private final PhmDataScopeService dataScope;
    private final PhmService phmService;
    private final ModelReleaseMapper models;
    private final String gearUrl;
    private final String bearingUrl;
    private final String token;
    private final RestClient restClient;

    public SensorDiagnosisLowCodeActionHandler(PhmAttachmentStorageService attachments,
        PhmDataScopeService dataScope, PhmService phmService, ModelReleaseMapper models,
        @Value("${sensor.inference.gear-url:}") String gearUrl,
        @Value("${sensor.inference.bearing-url:}") String bearingUrl,
        @Value("${sensor.inference.internal-token:}") String token,
        @Value("${sensor.inference.connect-timeout-ms:5000}") int connectTimeoutMs,
        @Value("${sensor.inference.read-timeout-ms:120000}") int readTimeoutMs)
    {
        this.attachments = attachments; this.dataScope = dataScope; this.phmService = phmService;
        this.models = models; this.gearUrl = gearUrl; this.bearingUrl = bearingUrl; this.token = token;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs); factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override public String code() { return "sensor.diagnosis.run"; }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input, LowCodeActionContext context) throws Exception
    {
        if (input == null) throw new IllegalArgumentException("诊断输入不能为空");
        Long attachmentId = longValue(input.get("attachmentId"));
        PhmAttachmentEntity attachment = attachments.getAccessibleDiagnosisInput(attachmentId);
        if (attachment == null) throw new IllegalArgumentException("attachmentId 无效或无权访问");
        String deviceCode = required(input.get("deviceCode"), "deviceCode");
        PhmDeviceScopeQuery query = new PhmDeviceScopeQuery(); query.setDeviceCode(deviceCode);
        PhmDeviceEntity device = dataScope.getDevice(query);
        if (device == null || attachment.getBizId() != null && !attachment.getBizId().equals(device.getId()))
            throw new IllegalArgumentException("诊断输入附件与授权设备不匹配");
        Long pointId = longValue(input.get("pointId"));
        PhmMeasurePointEntity point = phmService.listMeasurePoints(device.getId()).stream()
            .filter(item -> pointId != null && pointId.equals(item.getId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("测点不存在或不属于指定设备"));
        if (!"vibration".equalsIgnoreCase(point.getSignalType()) || point.getChannelId() == null)
            throw new IllegalArgumentException("所选测点不是已配置通道的振动测点");
        if (attachment.getPointId() != null && !attachment.getPointId().equals(point.getId()))
            throw new IllegalArgumentException("诊断附件与测点不匹配");

        String modelType = input.get("modelType") == null ? "gear" : String.valueOf(input.get("modelType"));
        if (!MODEL_TYPES.contains(modelType)) throw new IllegalArgumentException("modelType 仅支持 gear 或 bearing");
        String requestedVersion = input.get("modelVersion") == null ? null : String.valueOf(input.get("modelVersion"));
        ModelReleaseEntity model = resolveModel(modelType, requestedVersion);
        String requestId = context.idempotencyKey() == null || context.idempotencyKey().isBlank()
            ? UUID.randomUUID().toString() : context.idempotencyKey();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attachmentId", attachment.getId());
        payload.put("filePath", attachments.trustedContentPath(attachment).toString());
        payload.put("filename", attachment.getFileName()); payload.put("deviceCode", device.getDeviceCode());
        payload.put("pointId", point.getId()); payload.put("channelId", point.getChannelId());
        payload.put("modelType", modelType); payload.put("modelVersion", model.getSemanticVersion());
        payload.put("modelArtifactUri", model.getArtifactUri()); payload.put("modelArtifactSha256", model.getFileSha256());
        payload.put("requestId", requestId); payload.put("taskId", requestId);
        payload.put("sampleTime", DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, new Date()));

        Map<String, Object> response = callInference(modelType, payload, requestId);
        if (Boolean.FALSE.equals(response.get("success"))) throw new IOException(String.valueOf(response.get("message")));
        Map<String, Object> raw = response.get("data") instanceof Map ? (Map<String, Object>) response.get("data") : response;
        validateResult(raw, modelType, model.getSemanticVersion());
        Map<String, Object> normalized = normalize(raw, payload);
        phmService.syncDiagnosisResult(normalized);
        phmService.recalculateDiagnosisState(device.getDeviceCode());
        return normalized;
    }

    private ModelReleaseEntity resolveModel(String type, String version)
    {
        LambdaQueryWrapper<ModelReleaseEntity> wrapper = new LambdaQueryWrapper<ModelReleaseEntity>()
            .eq(ModelReleaseEntity::getModelType, type).in(ModelReleaseEntity::getStatus, EXECUTABLE)
            .eq(version != null && !version.isBlank(), ModelReleaseEntity::getSemanticVersion, version)
            .eq(version == null || version.isBlank(), ModelReleaseEntity::getStatus, "ACTIVE")
            .orderByDesc(ModelReleaseEntity::getCreateTime).last("limit 1");
        ModelReleaseEntity model = models.selectOne(wrapper);
        if (model == null || blank(model.getArtifactUri()) || blank(model.getFileSha256()))
            throw new IllegalArgumentException("所选模型不存在、不可执行或缺少可信制品");
        return model;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callInference(String type, Map<String, Object> payload, String requestId) throws IOException
    {
        String url = "bearing".equals(type) ? bearingUrl : gearUrl;
        if (blank(url)) throw new IOException("未配置 " + type + " 推理服务地址");
        String response = restClient.post().uri(url).header("X-Internal-Token", token)
            .header("X-Request-Id", requestId).header("X-Task-Id", requestId)
            .contentType(MediaType.APPLICATION_JSON).body(JSON.toJSONString(payload)).retrieve()
            .onStatus(status -> status.value() >= 400, (req, resp) -> { throw new IOException("Python 推理服务返回 HTTP " + resp.getStatusCode()); })
            .body(String.class);
        return JSON.parseObject(response, Map.class);
    }

    private void validateResult(Map<String, Object> result, String modelType, String modelVersion) throws IOException
    {
        if (result == null || !modelType.equalsIgnoreCase(text(result.get("modelType")))
            || !modelVersion.equals(text(result.get("modelVersion")))) throw new IOException("推理服务实际模型与请求不一致");
        required(result.get("taskId"), "taskId"); required(result.get("deviceCode"), "deviceCode");
        first(result, "diagnosisResult", "diagnosisName", "label");
        range(result.get("confidence"), "confidence"); range(result.get("healthIndex"), "healthIndex");
        String risk = required(result.get("riskLevel"), "riskLevel");
        if (!Set.of("低", "中", "高", "low", "medium", "high").contains(risk)) throw new IOException("riskLevel 非法");
    }

    private Map<String, Object> normalize(Map<String, Object> result, Map<String, Object> payload)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("deviceCode", payload.get("deviceCode")); value.put("taskId", result.get("taskId"));
        value.put("requestId", result.getOrDefault("requestId", payload.get("requestId")));
        value.put("pointId", payload.get("pointId")); value.put("channelId", payload.get("channelId"));
        value.put("sampleTime", result.get("sampleTime")); value.put("modelVersion", result.get("modelVersion"));
        value.put("modelType", result.get("modelType")); value.put("diagnosisResult", first(result, "diagnosisResult", "diagnosisName", "label"));
        value.put("diagnosisDetail", firstOptional(result, "diagnosisDetail", "diagnosis_detail"));
        value.put("confidence", doubleValue(result.get("confidence"))); value.put("healthIndex", doubleValue(result.get("healthIndex")));
        value.put("riskLevel", result.get("riskLevel")); value.put("alarmLevel", firstOptional(result, "alarmLevel", "alarm_level"));
        value.put("status", "SUCCEEDED"); value.put("resultStatus", "VALID"); value.put("filePath", payload.get("filename"));
        value.put("waveform", result.getOrDefault("time_data", List.of())); value.put("frequencyAxis", result.getOrDefault("freq_axis", List.of()));
        value.put("spectrum", result.getOrDefault("freq_data", List.of())); value.put("evidence", result.getOrDefault("evidence", List.of()));
        value.put("latestRms", result.get("rms")); value.put("latestPeak", result.get("peak"));
        value.put("sampleRate", result.getOrDefault("sampleRate", result.get("sample_rate")));
        return value;
    }

    private String first(Map<String, Object> data, String... keys) { String value = firstOptional(data, keys); if (blank(value)) throw new IllegalArgumentException("推理结果缺少 " + String.join("/", keys)); return value; }
    private String firstOptional(Map<String, Object> data, String... keys) { for (String key : keys) if (!blank(text(data.get(key)))) return text(data.get(key)); return null; }
    private String required(Object value, String field) { if (blank(text(value))) throw new IllegalArgumentException(field + " 为必填项"); return text(value).trim(); }
    private void range(Object value, String field) throws IOException { double number = doubleValue(value); if (!Double.isFinite(number) || number < 0 || number > 100) throw new IOException(field + " 超出0到100范围"); }
    private double doubleValue(Object value) { try { return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value)); } catch (Exception ex) { throw new IllegalArgumentException("数值字段无法解析"); } }
    private Long longValue(Object value) { try { return value == null ? null : value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); } catch (Exception ex) { return null; } }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
