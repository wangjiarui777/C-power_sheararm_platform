package com.ruoyi.sensor.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AttachmentVirusScanner
{
    private final boolean required;
    private final String command;
    private final String arguments;
    private final Duration timeout;

    public AttachmentVirusScanner(
        @Value("${sensor.attachment.scan-required:false}") boolean required,
        @Value("${sensor.attachment.virus-scan-command:}") String command,
        @Value("${sensor.attachment.virus-scan-arguments:}") String arguments,
        @Value("${sensor.attachment.virus-scan-timeout-seconds:120}") long timeoutSeconds)
    {
        this.required = required;
        this.command = command;
        this.arguments = arguments;
        this.timeout = Duration.ofSeconds(Math.max(10, timeoutSeconds));
    }

    public String scan(Path file) throws Exception
    {
        if (!StringUtils.hasText(command))
        {
            if (required)
            {
                throw new IllegalStateException("病毒扫描器未配置");
            }
            return "SKIPPED";
        }
        List<String> processCommand = new ArrayList<>();
        processCommand.add(command);
        if (StringUtils.hasText(arguments))
        {
            for (String argument : arguments.split(","))
            {
                processCommand.add(argument.trim().replace("{file}", file.toString()));
            }
        }
        else
        {
            processCommand.add(file.toString());
        }
        Process process = new ProcessBuilder(processCommand)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS))
        {
            process.destroyForcibly();
            throw new IllegalStateException("病毒扫描超时");
        }
        if (process.exitValue() != 0)
        {
            throw new SecurityException("文件未通过病毒扫描，退出码: " + process.exitValue());
        }
        return "CLEAN";
    }
}
