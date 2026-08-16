package com.ruoyi.sensor.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Starts only the optional Python inference sidecar. MAT TCP is Spring-managed. */
@Component
public class SensorExternalProcessStartup implements ApplicationRunner, DisposableBean
{
    private static final Logger log = LoggerFactory.getLogger(SensorExternalProcessStartup.class);

    @Value("${sensor.startup.inference.enabled:true}")
    private boolean inferenceEnabled;
    @Value("${sensor.startup.inference.dir:ruoyi-sensor/inference}")
    private String inferenceDir;
    @Value("${sensor.startup.inference.script:inference_service.py}")
    private String inferenceScript;
    @Value("${sensor.startup.inference.python-command:python}")
    private String pythonCommand;

    private final List<Process> childProcesses = new ArrayList<>();

    @Override
    public void run(ApplicationArguments args)
    {
        if (inferenceEnabled) startInferenceService();
    }

    private void startInferenceService()
    {
        Path workDir = resolvePath(inferenceDir);
        startProcess("python-inference", workDir, List.of(pythonCommand, inferenceScript));
    }

    private void startProcess(String name, Path workDir, List<String> command)
    {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        try
        {
            Process process = builder.start();
            childProcesses.add(process);
            drainOutputAsync(name, process);
            log.info("Started {} with command {} in {}", name, command, workDir);
        }
        catch (IOException ex)
        {
            log.warn("Unable to start {} with command {} in {}", name, command, workDir, ex);
        }
    }

    private Path resolvePath(String configuredPath)
    {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) return path.normalize();
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (userDir.resolve(path).toFile().exists()) return userDir.resolve(path).normalize();
        return userDir.getParent() == null ? userDir.resolve(path).normalize()
            : userDir.getParent().resolve(path).normalize();
    }

    private void drainOutputAsync(String name, Process process)
    {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset())))
            {
                String line;
                while ((line = reader.readLine()) != null) log.info("[{}] {}", name, line);
            }
            catch (IOException ex) { log.debug("Output reader for {} stopped", name, ex); }
        }, "sensor-process-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void destroy()
    {
        for (Process process : childProcesses)
        {
            if (!process.isAlive()) continue;
            process.destroy();
            try
            {
                if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS))
                    process.destroyForcibly();
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
