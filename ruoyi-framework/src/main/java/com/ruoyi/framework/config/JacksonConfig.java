package com.ruoyi.framework.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps database identifiers exact when JSON is consumed by JavaScript.
 * Snowflake IDs exceed Number.MAX_SAFE_INTEGER and must travel as strings.
 */
@Configuration
public class JacksonConfig
{
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longIdJacksonCustomizer()
    {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}


