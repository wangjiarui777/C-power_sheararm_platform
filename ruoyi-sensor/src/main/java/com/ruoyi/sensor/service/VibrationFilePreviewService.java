package com.ruoyi.sensor.service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.entity.SensorIngestFileEntity;
import com.ruoyi.sensor.mapper.SensorIngestFileMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Read-only signal preview for accepted vibration attachments.
 * It deliberately does not create an inference record or change device state.
 */
@Service
public class VibrationFilePreviewService
{
    private final PhmAttachmentStorageService attachmentStorageService;
    private final SensorIngestFileMapper ingestFileMapper;
    private final String previewUrl;
    private final String internalToken;
    private final RestClient restClient;

    public VibrationFilePreviewService(PhmAttachmentStorageService attachmentStorageService,
        SensorIngestFileMapper ingestFileMapper,
        @Value("${sensor.inference.preview-url:}") String configuredPreviewUrl,
        @Value("${sensor.inference.gear-url:}") String gearInferUrl,
        @Value("${sensor.inference.bearing-url:}") String bearingInferUrl,
        @Value("${sensor.inference.internal-token:}") String internalToken,
        @Value("${sensor.inference.connect-timeout-ms:5000}") int connectTimeoutMs,
        @Value("${sensor.inference.read-timeout-ms:120000}") int readTimeoutMs)
    {
        this.attachmentStorageService = attachmentStorageService;
        this.ingestFileMapper = ingestFileMapper;
        this.previewUrl = resolvePreviewUrl(configuredPreviewUrl, gearInferUrl, bearingInferUrl);
        this.internalToken = internalToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> preview(Long attachmentId, PhmDeviceEntity device,
        PhmMeasurePointEntity point, int maxPoints)
    {
        if (attachmentId == null || device == null || point == null)
        {
            throw new ServiceException("文件、设备和测点不能为空");
        }
        PhmAttachmentEntity attachment = attachmentStorageService.getAccessibleDiagnosisInput(attachmentId);
        SensorIngestFileEntity ledger = ingestFileMapper.selectOne(new LambdaQueryWrapper<SensorIngestFileEntity>()
            .eq(SensorIngestFileEntity::getAttachmentId, attachmentId)
            .last("LIMIT 1"));
        if (attachment == null || ledger == null
            || !("ACCEPTED".equals(ledger.getStatus()) || "DUPLICATE".equals(ledger.getStatus()))
            || !device.getId().equals(attachment.getBizId())
            || !device.getId().equals(ledger.getDeviceId())
            || !point.getId().equals(ledger.getPointId())
            || (attachment.getPointId() != null && !point.getId().equals(attachment.getPointId())))
        {
            throw new ServiceException("文件不存在、未完成校验或无权访问");
        }
        if (previewUrl == null || previewUrl.isBlank())
        {
            throw new ServiceException("振动文件分析服务未配置");
        }

        final String filePath;
        try
        {
            filePath = attachmentStorageService.trustedContentPath(attachment).toString();
        }
        catch (IOException ex)
        {
            throw new ServiceException("振动文件内容不可用");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("filePath", filePath);
        payload.put("fileName", attachment.getFileName());
        payload.put("channelId", attachment.getChannelId());
        payload.put("maxPoints", Math.max(256, Math.min(maxPoints, 4096)));
        String response;
        try
        {
            response = restClient.post()
                .uri(previewUrl)
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(payload))
                .retrieve()
                .onStatus(status -> status.value() >= 400,
                    (request, serverResponse) -> { throw new ServiceException("振动文件分析服务不可用"); })
                .body(String.class);
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("振动文件分析服务不可用");
        }

        Map<String, Object> result = JSON.parseObject(response, Map.class);
        if (result == null || Boolean.FALSE.equals(result.get("success")))
        {
            String message = result == null ? "空响应" : String.valueOf(result.getOrDefault("message", "解析失败"));
            throw new ServiceException("振动文件解析失败: " + message);
        }
        Object data = result.get("data");
        if (!(data instanceof Map))
        {
            throw new ServiceException("振动文件解析结果无效");
        }
        Map<String, Object> preview = new LinkedHashMap<>((Map<String, Object>) data);
        preview.put("attachmentId", attachment.getId());
        preview.put("fileStatus", ledger.getStatus());
        preview.put("source", "FILE");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", attachment.getId());
        metadata.put("fileName", attachment.getFileName());
        metadata.put("fileExt", attachment.getFileExt());
        metadata.put("fileSize", attachment.getFileSize());
        metadata.put("sha256", maskHash(attachment.getSha256()));
        metadata.put("channelId", attachment.getChannelId());
        metadata.put("receivedTime", ledger.getReceivedTime());
        preview.put("attachment", metadata);
        return preview;
    }

    private String maskHash(String hash)
    {
        if (hash == null || hash.length() < 14)
        {
            return hash;
        }
        return hash.substring(0, 8) + "…" + hash.substring(hash.length() - 6);
    }

    private String resolvePreviewUrl(String configured, String gearUrl, String bearingUrl)
    {
        if (configured != null && !configured.isBlank())
        {
            return configured.trim();
        }
        String fallback = gearUrl != null && !gearUrl.isBlank() ? gearUrl : bearingUrl;
        if (fallback == null || fallback.isBlank())
        {
            return "";
        }
        String normalized = fallback.trim();
        if (normalized.endsWith("/internal/infer"))
        {
            return normalized.substring(0, normalized.length() - "/internal/infer".length()) + "/internal/preview";
        }
        return normalized.endsWith("/") ? normalized + "internal/preview" : normalized + "/internal/preview";
    }
}
