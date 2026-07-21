package com.ruoyi.sensor.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class DiagnosisAsyncConfig
{
    @Bean(name = "diagnosisExecutor")
    public Executor diagnosisExecutor(
        @Value("${sensor.diagnosis.executor.core-pool-size:4}") int corePoolSize,
        @Value("${sensor.diagnosis.executor.max-pool-size:4}") int maxPoolSize,
        @Value("${sensor.diagnosis.executor.queue-capacity:100}") int queueCapacity)
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("diagnosis-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
