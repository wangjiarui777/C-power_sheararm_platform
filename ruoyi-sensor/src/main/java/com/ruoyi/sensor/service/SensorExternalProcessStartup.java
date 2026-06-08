package com.ruoyi.sensor.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Starts the Python inference sidecar and MAT receiver together with the backend.
 */
@Component
public class SensorExternalProcessStartup implements ApplicationRunner, DisposableBean
{
    private static final Logger log = LoggerFactory.getLogger(SensorExternalProcessStartup.class);

    @Value("${sensor.startup.inference.enabled:true}")
    private boolean inferenceEnabled;

    @Value("${sensor.startup.receiver.enabled:true}")
    private boolean receiverEnabled;

    @Value("${sensor.startup.inference.dir:ruoyi-sensor/inference}")
    private String inferenceDir;

    @Value("${sensor.startup.inference.script:inference_service.py}")
    private String inferenceScript;

    @Value("${sensor.startup.inference.python-command:python}")
    private String pythonCommand;

    @Value("${sensor.startup.receiver.dir:ruoyi-sensor/inference/get}")
    private String receiverDir;

    @Value("${sensor.startup.receiver.source:CwruMatReceiver.java}")
    private String receiverSource;

    @Value("${sensor.startup.receiver.port:8888}")
    private int receiverPort;

    @Value("${sensor.startup.receiver.save-dir:got}")
    private String receiverSaveDir;

    @Value("${sensor.startup.receiver.javac-command:javac}")
    private String javacCommand;

    @Value("${sensor.startup.receiver.java-command:java}")
    private String javaCommand;

    private final List<Process> childProcesses = new ArrayList<>();

    @Override
    public void run(ApplicationArguments args)
    {
        if (inferenceEnabled)
        {
            startInferenceService();
        }
        if (receiverEnabled)
        {
            startMatReceiver();
        }
    }

    private void startInferenceService()
    {
        Path workDir = resolvePath(inferenceDir);
        startProcess("python-inference", workDir, Arrays.asList(pythonCommand, inferenceScript));
    }

    private void startMatReceiver()
    {
        Path workDir = resolvePath(receiverDir);
        if (!compileReceiver(workDir))
        {
            log.warn("CwruMatReceiver compile failed, receiver process will not be started");
            return;
        }
        startProcess("cwru-mat-receiver", workDir,
                Arrays.asList(javaCommand, "CwruMatReceiver", String.valueOf(receiverPort), receiverSaveDir));
    }

    private boolean compileReceiver(Path workDir)
    {
        ProcessBuilder builder = new ProcessBuilder(javacCommand, "-encoding", "UTF-8", receiverSource);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        try
        {
            Process process = builder.start();
            String output = readProcessOutput(process);
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished)
            {
                process.destroyForcibly();
                log.warn("CwruMatReceiver compile timed out in {}", workDir);
                return false;
            }
            if (process.exitValue() != 0)
            {
                log.warn("CwruMatReceiver compile exited with code {}. Output: {}", process.exitValue(), output);
                return false;
            }
            log.info("CwruMatReceiver compiled successfully in {}", workDir);
            return true;
        }
        catch (Exception ex)
        {
            log.warn("Unable to compile CwruMatReceiver in {}", workDir, ex);
            return false;
        }
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
        if (path.isAbsolute())
        {
            return path.normalize();
        }

        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> bases = new ArrayList<>();
        bases.add(userDir);
        if (userDir.getParent() != null)
        {
            bases.add(userDir.getParent());
        }

        for (Path base : bases)
        {
            Path candidate = base.resolve(path).normalize();
            if (candidate.toFile().exists())
            {
                return candidate;
            }
        }
        return userDir.resolve(path).normalize();
    }

    private String readProcessOutput(Process process) throws IOException
    {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset())))
        {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (builder.length() > 0)
                {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
            }
            return builder.toString();
        }
    }

    private void drainOutputAsync(String name, Process process)
    {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset())))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    log.info("[{}] {}", name, line);
                }
            }
            catch (IOException ex)
            {
                log.debug("Output reader for {} stopped", name, ex);
            }
        }, "sensor-process-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void destroy()
    {
        for (Process process : childProcesses)
        {
            if (process.isAlive())
            {
                process.destroy();
                try
                {
                    if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS))
                    {
                        process.destroyForcibly();
                    }
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
