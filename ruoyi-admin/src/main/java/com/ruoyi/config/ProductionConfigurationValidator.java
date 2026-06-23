package com.ruoyi.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Refuses to start the production profile with development defaults or missing secrets.
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements ApplicationRunner
{
    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment)
    {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args)
    {
        List<String> errors = validate(environment);
        if (!errors.isEmpty())
        {
            throw new IllegalStateException("Production configuration is invalid:\n - "
                    + String.join("\n - ", errors));
        }
    }

    static List<String> validate(Environment env)
    {
        List<String> errors = new ArrayList<>();
        requireSecret(env, errors, "token.secret", 32);
        validatePreviousJwtSecrets(env, errors);
        requireSecret(env, errors, "sensor.collector.master-key", 32);
        requireSecret(env, errors, "sensor.inference.internal-token", 32);
        requireSecret(env, errors, "spring.datasource.druid.master.password", 8);
        requireSecret(env, errors, "spring.data.redis.password", 8);
        requireSecret(env, errors, "sensor.iotdb.password", 8);

        requireValue(env, errors, "spring.datasource.druid.master.url");
        requireValue(env, errors, "spring.datasource.druid.master.username");
        requireValue(env, errors, "spring.data.redis.host");
        requireValue(env, errors, "sensor.iotdb.node-urls");
        requireValue(env, errors, "sensor.iotdb.username");
        validateWritablePath(env, errors, "ruoyi.profile");
        validateWritablePath(env, errors, "logging.file.path");
        validateWritablePath(env, errors, "sensor.attachment.root");
        validateWritablePath(env, errors, "sensor.attachment.virus-scan-command");
        requireValue(env, errors, "sensor.attachment.virus-scan-arguments");
        validateOrigins(env, errors, "cors.allowed-origins");
        validateOrigins(env, errors, "sensor.websocket.allowed-origins");
        validateInternalInferenceUrl(env, errors);
        validateTcpBinding(env, errors);
        return errors;
    }

    private static void requireValue(Environment env, List<String> errors, String key)
    {
        if (!StringUtils.hasText(env.getProperty(key)))
        {
            errors.add(key + " must be configured");
        }
    }

    private static void requireSecret(Environment env, List<String> errors, String key, int minimumBytes)
    {
        String value = env.getProperty(key);
        if (!StringUtils.hasText(value))
        {
            errors.add(key + " must be configured");
            return;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < minimumBytes)
        {
            errors.add(key + " must contain at least " + minimumBytes + " UTF-8 bytes");
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.contains("change-me") || normalized.contains("replace-with")
                || normalized.equals("admin123") || normalized.equals("123456")
                || normalized.equals("root") || normalized.startsWith("dev-"))
        {
            errors.add(key + " must not use a development/default value");
        }
    }

    private static void validatePreviousJwtSecrets(Environment env, List<String> errors)
    {
        String previous = env.getProperty("token.previous-secrets");
        if (!StringUtils.hasText(previous))
        {
            return;
        }
        for (String value : previous.split(","))
        {
            if (value.trim().getBytes(StandardCharsets.UTF_8).length < 32)
            {
                errors.add("every token.previous-secrets entry must contain at least 32 UTF-8 bytes");
                return;
            }
        }
    }

    private static void validateOrigins(Environment env, List<String> errors, String key)
    {
        String value = env.getProperty(key);
        if (!StringUtils.hasText(value))
        {
            errors.add(key + " must be configured");
            return;
        }
        boolean invalid = Arrays.stream(value.split(","))
                .map(String::trim)
                .anyMatch(origin -> origin.equals("*")
                        || origin.contains("localhost")
                        || origin.contains("127.0.0.1"));
        if (invalid)
        {
            errors.add(key + " must contain production origins only");
        }
    }

    private static void validateWritablePath(Environment env, List<String> errors, String key)
    {
        String value = env.getProperty(key);
        if (!StringUtils.hasText(value))
        {
            errors.add(key + " must be configured");
            return;
        }
        try
        {
            if (!Path.of(value).isAbsolute())
            {
                errors.add(key + " must be an absolute path");
            }
        }
        catch (Exception ex)
        {
            errors.add(key + " is not a valid filesystem path");
        }
    }

    private static void validateInternalInferenceUrl(Environment env, List<String> errors)
    {
        String value = env.getProperty("sensor.inference.gear-url");
        if (!StringUtils.hasText(value))
        {
            errors.add("sensor.inference.gear-url must be configured");
            return;
        }
        if (!(value.startsWith("http://127.0.0.1:") || value.startsWith("http://localhost:")))
        {
            errors.add("sensor.inference.gear-url must target the local internal inference service");
        }
    }

    private static void validateTcpBinding(Environment env, List<String> errors)
    {
        if (!env.getProperty("sensor.channel-tcp.enabled", Boolean.class, false))
        {
            return;
        }
        String bindAddress = env.getProperty("sensor.channel-tcp.bind-address");
        if (!StringUtils.hasText(bindAddress))
        {
            errors.add("sensor.channel-tcp.bind-address must be configured when TCP ingestion is enabled");
        }
        else if ("0.0.0.0".equals(bindAddress.trim()) || "::".equals(bindAddress.trim()))
        {
            errors.add("sensor.channel-tcp.bind-address must target the dedicated industrial interface");
        }
    }
}
