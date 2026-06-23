package com.ruoyi.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidatorTest
{
    @Test
    void rejectsMissingAndDevelopmentSecrets()
    {
        MockEnvironment environment = validEnvironment()
                .withProperty("token.secret", "short")
                .withProperty("sensor.collector.master-key", "dev-collector-key");

        List<String> errors = ProductionConfigurationValidator.validate(environment);

        assertTrue(errors.stream().anyMatch(item -> item.contains("token.secret")));
        assertTrue(errors.stream().anyMatch(item -> item.contains("sensor.collector.master-key")));
    }

    @Test
    void acceptsCompleteProductionConfiguration()
    {
        List<String> errors = ProductionConfigurationValidator.validate(validEnvironment());

        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
    }

    @Test
    void rejectsPublicInferenceAndDevelopmentOrigins()
    {
        MockEnvironment environment = validEnvironment()
                .withProperty("sensor.inference.gear-url", "https://infer.example.com/internal/infer")
                .withProperty("cors.allowed-origins", "http://localhost:9528");

        List<String> errors = ProductionConfigurationValidator.validate(environment);

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(item -> item.contains("local internal inference")));
        assertTrue(errors.stream().anyMatch(item -> item.contains("production origins")));
    }

    private MockEnvironment validEnvironment()
    {
        return new MockEnvironment()
                .withProperty("token.secret", "jwt-secret-0123456789-0123456789-abcdef")
                .withProperty("sensor.collector.token", "collector-token-0123456789-0123456789")
                .withProperty("sensor.collector.master-key", "collector-master-0123456789-0123456789")
                .withProperty("sensor.inference.internal-token", "inference-token-0123456789-0123456789")
                .withProperty("spring.datasource.druid.master.password", "mysql-password")
                .withProperty("spring.datasource.druid.master.url", "jdbc:mysql://db:3306/ry-yue")
                .withProperty("spring.datasource.druid.master.username", "ruoyi")
                .withProperty("spring.data.redis.host", "redis")
                .withProperty("spring.data.redis.password", "redis-password")
                .withProperty("sensor.iotdb.node-urls", "iotdb:6667")
                .withProperty("sensor.iotdb.username", "ruoyi")
                .withProperty("sensor.iotdb.password", "iotdb-password")
                .withProperty("ruoyi.profile", "D:/ruoyi/data")
                .withProperty("logging.file.path", "D:/ruoyi/logs")
                .withProperty("sensor.attachment.root", "D:/ruoyi-secure/attachments")
                .withProperty("sensor.attachment.virus-scan-command", "C:/Program Files/Windows Defender/MpCmdRun.exe")
                .withProperty("sensor.attachment.virus-scan-arguments", "-Scan,-ScanType,3,-File,{file},-DisableRemediation")
                .withProperty("cors.allowed-origins", "https://phm.example.internal")
                .withProperty("sensor.websocket.allowed-origins", "https://phm.example.internal")
                .withProperty("sensor.inference.gear-url", "http://127.0.0.1:5000/internal/infer");
    }
}
