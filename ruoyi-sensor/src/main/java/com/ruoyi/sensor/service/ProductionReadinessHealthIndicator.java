package com.ruoyi.sensor.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component("phmProductionReadiness")
public class ProductionReadinessHealthIndicator implements HealthIndicator
{
    private final ObjectProvider<MatFileReceiverService> matReceiverProvider;
    private final Path attachmentRoot;
    private final String inferenceHealthUrl;
    private final String inferenceToken;
    private final boolean inferenceRequired;
    private final RestClient restClient;

    public ProductionReadinessHealthIndicator(ObjectProvider<MatFileReceiverService> matReceiverProvider,
        @Value("${sensor.attachment.root:D:/ruoyi-secure/attachments}") String attachmentRoot,
        @Value("${sensor.inference.gear-url:}") String inferenceUrl,
        @Value("${sensor.inference.internal-token:}") String inferenceToken,
        @Value("${sensor.health.inference-required:false}") boolean inferenceRequired)
    {
        this.matReceiverProvider = matReceiverProvider;
        this.attachmentRoot = Path.of(attachmentRoot).toAbsolutePath().normalize();
        this.inferenceHealthUrl = inferenceUrl == null ? ""
            : inferenceUrl.replaceAll("/internal/infer/?$", "/internal/health/ready");
        this.inferenceToken = inferenceToken;
        this.inferenceRequired = inferenceRequired;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Health health()
    {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean up = true;
        try
        {
            Files.createDirectories(attachmentRoot);
            long total = attachmentRoot.toFile().getTotalSpace();
            long usable = attachmentRoot.toFile().getUsableSpace();
            details.put("attachmentDiskUsableBytes", usable);
            details.put("attachmentDiskUsedPercent", total <= 0 ? null
                : Math.round((1D - usable / (double) total) * 1000D) / 10D);
            if (total > 0 && usable / (double) total < 0.10D)
            {
                up = false;
            }
        }
        catch (Exception ex)
        {
            details.put("attachmentStorage", "unavailable");
            up = false;
        }
        MatFileReceiverService matReceiver = matReceiverProvider.getIfAvailable();
        details.put("matReceiverEnabled", matReceiver != null);
        details.put("matReceiverListening", matReceiver != null && matReceiver.isRunning());
        if (matReceiver != null)
        {
            details.putAll(matReceiver.healthDetails());
        }
        if (matReceiver == null || !matReceiver.isRunning())
        {
            up = false;
        }
        if (inferenceRequired)
        {
            try
            {
                Map<?, ?> response = restClient.get().uri(inferenceHealthUrl)
                    .header("X-Internal-Token", inferenceToken)
                    .retrieve().body(Map.class);
                details.put("inference", response == null ? "empty" : response.get("status"));
            }
            catch (Exception ex)
            {
                details.put("inference", "unavailable");
                up = false;
            }
        }
        return (up ? Health.up() : Health.down()).withDetails(details).build();
    }

}
