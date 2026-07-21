package com.ruoyi.sensor.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.mapper.PhmDeviceMapper;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Imports stable diagnosis files from <inbox>/<deviceCode>/<pointCode> into secure attachment storage. */
@Service
@ConditionalOnProperty(prefix = "sensor.diagnosis.ingest", name = "enabled", havingValue = "true")
public class DiagnosisFileIngestionService
{
    private static final Logger log = LoggerFactory.getLogger(DiagnosisFileIngestionService.class);
    private static final DateTimeFormatter REJECTED_TIME =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final Path inboxRoot;
    private final Path rejectedRoot;
    private final long stableWaitMs;
    private final PhmAttachmentStorageService attachmentStorageService;
    private final PhmDeviceMapper deviceMapper;
    private final PhmMeasurePointMapper pointMapper;
    private final Map<Path, ObservedFile> observations = new HashMap<>();

    public DiagnosisFileIngestionService(
        @Value("${sensor.diagnosis.ingest.root:./.local-data/diagnosis-inbox}") String inboxRoot,
        @Value("${sensor.diagnosis.ingest.stable-wait-ms:10000}") long stableWaitMs,
        @Value("${sensor.inference.allowed-input-roots:${INFERENCE_ALLOWED_INPUT_ROOTS:}}") String allowedInputRoots,
        PhmAttachmentStorageService attachmentStorageService,
        PhmDeviceMapper deviceMapper,
        PhmMeasurePointMapper pointMapper) throws IOException
    {
        this.inboxRoot = Path.of(inboxRoot).toAbsolutePath().normalize();
        this.rejectedRoot = this.inboxRoot.resolve("rejected").normalize();
        this.stableWaitMs = Math.max(0, stableWaitMs);
        this.attachmentStorageService = attachmentStorageService;
        this.deviceMapper = deviceMapper;
        this.pointMapper = pointMapper;
        Files.createDirectories(this.inboxRoot);
        Files.createDirectories(this.rejectedRoot);
        validateInferenceRoots(allowedInputRoots);
    }

    @Scheduled(
        fixedDelayString = "${sensor.diagnosis.ingest.scan-interval-ms:5000}",
        initialDelayString = "${sensor.diagnosis.ingest.scan-interval-ms:5000}")
    public synchronized void scan()
    {
        Set<Path> seen = new HashSet<>();
        try (Stream<Path> deviceDirectories = Files.list(inboxRoot))
        {
            deviceDirectories
                .filter(path -> !path.equals(rejectedRoot))
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .forEach(path -> scanDeviceDirectory(path, seen));
        }
        catch (Exception ex)
        {
            log.error("扫描诊断文件接入目录失败: {}", inboxRoot, ex);
        }
        observations.keySet().removeIf(path -> !seen.contains(path));
    }

    private void scanDeviceDirectory(Path deviceDirectory, Set<Path> seen)
    {
        String deviceCode = deviceDirectory.getFileName().toString();
        try (Stream<Path> entries = Files.list(deviceDirectory))
        {
            entries.forEach(path -> {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                {
                    scanPointDirectory(path, deviceCode, seen);
                }
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isCandidate(path))
                {
                    reject(path, deviceCode, null, "缺少测点目录，目录必须为 <设备编码>/<测点编码>/文件");
                }
            });
        }
        catch (Exception ex)
        {
            log.error("扫描设备诊断目录失败: {}", deviceDirectory, ex);
        }
    }

    private void scanPointDirectory(Path pointDirectory, String deviceCode, Set<Path> seen)
    {
        String pointCode = pointDirectory.getFileName().toString();
        try (Stream<Path> files = Files.list(pointDirectory))
        {
            files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(this::isCandidate)
                .forEach(path -> observe(path, deviceCode, pointCode, seen));
        }
        catch (Exception ex)
        {
            log.error("扫描测点诊断目录失败: {}", pointDirectory, ex);
        }
    }

    private boolean isCandidate(Path path)
    {
        String name = path.getFileName().toString().toLowerCase();
        return !name.endsWith(".part");
    }

    private void observe(Path source, String deviceCode, String pointCode, Set<Path> seen)
    {
        Path normalized = source.toAbsolutePath().normalize();
        seen.add(normalized);
        try
        {
            if (!normalized.startsWith(inboxRoot) || Files.isSymbolicLink(normalized))
            {
                reject(normalized, deviceCode, pointCode, "文件路径不在接入目录内或属于符号链接");
                return;
            }
            long size = Files.size(normalized);
            long modified = Files.getLastModifiedTime(normalized, LinkOption.NOFOLLOW_LINKS).toMillis();
            long now = System.currentTimeMillis();
            ObservedFile previous = observations.get(normalized);
            if (previous == null || previous.size != size || previous.modified != modified)
            {
                observations.put(normalized, new ObservedFile(size, modified, now));
                return;
            }
            if (now - previous.firstStableAt < stableWaitMs)
            {
                return;
            }
            observations.remove(normalized);
            ingest(normalized, deviceCode, pointCode);
        }
        catch (Exception ex)
        {
            observations.remove(normalized);
            reject(normalized, deviceCode, pointCode, ex.getMessage());
        }
    }

    private void ingest(Path source, String deviceCode, String pointCode) throws Exception
    {
        long size = Files.size(source);
        if (size <= 0 || size > 128L * 1024 * 1024)
        {
            throw new IllegalArgumentException("诊断文件大小必须大于 0 且不能超过 128MB");
        }
        PhmDeviceEntity device = deviceMapper.selectOne(new LambdaQueryWrapper<PhmDeviceEntity>()
            .eq(PhmDeviceEntity::getDeviceCode, deviceCode)
            .last("LIMIT 1"));
        if (device == null)
        {
            throw new IllegalArgumentException("目录名对应的设备不存在: " + deviceCode);
        }
        PhmMeasurePointEntity point = pointMapper.selectOne(new LambdaQueryWrapper<PhmMeasurePointEntity>()
            .eq(PhmMeasurePointEntity::getDeviceId, device.getId())
            .eq(PhmMeasurePointEntity::getPointCode, pointCode)
            .eq(PhmMeasurePointEntity::getEnabled, true)
            .eq(PhmMeasurePointEntity::getSignalType, "vibration")
            .last("LIMIT 1"));
        if (point == null || point.getChannelId() == null)
        {
            throw new IllegalArgumentException("目录名对应的振动测点不存在、已停用或未配置通道: " + pointCode);
        }
        String sha256 = sha256(source);
        PhmAttachmentEntity duplicate = attachmentStorageService
            .findDiagnosisInputByDevicePointAndSha256(device.getId(), point.getId(), sha256);
        if (duplicate == null)
        {
            attachmentStorageService.importDiagnosisFile(source, device.getId(), point.getId(),
                point.getChannelId(), "system-ingest");
            log.info("服务器诊断文件已接入: deviceCode={}, pointCode={}, file={}",
                deviceCode, pointCode, source.getFileName());
        }
        else
        {
            log.info("忽略重复诊断文件: deviceCode={}, attachmentId={}, file={}",
                deviceCode, duplicate.getId(), source.getFileName());
        }
        Files.deleteIfExists(source);
    }

    private void reject(Path source, String deviceCode, String pointCode, String reason)
    {
        if (source == null || !Files.exists(source, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }
        try
        {
            Path targetDirectory = rejectedRoot.resolve(safeSegment(deviceCode)).normalize();
            if (pointCode != null && !pointCode.isBlank())
            {
                targetDirectory = targetDirectory.resolve(safeSegment(pointCode)).normalize();
            }
            Files.createDirectories(targetDirectory);
            String prefix = REJECTED_TIME.format(Instant.now()) + "-";
            Path target = targetDirectory.resolve(prefix + source.getFileName()).normalize();
            if (!target.startsWith(rejectedRoot))
            {
                throw new IOException("拒绝目录路径非法");
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(target.resolveSibling(target.getFileName() + ".reason.txt"),
                reason == null ? "未知接入错误" : reason, StandardCharsets.UTF_8);
            log.warn("诊断文件接入失败并已隔离: file={}, reason={}", target, reason);
        }
        catch (Exception rejectError)
        {
            log.error("隔离诊断文件失败: {}", source, rejectError);
        }
    }

    private String safeSegment(String value)
    {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String sha256(Path path) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path))
        {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0)
            {
                if (read > 0)
                {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void validateInferenceRoots(String configuredRoots)
    {
        if (configuredRoots == null || configuredRoots.isBlank())
        {
            throw new IllegalStateException(
                "已启用诊断目录接入，但未配置 INFERENCE_ALLOWED_INPUT_ROOTS");
        }
        Path objectsRoot = attachmentStorageService.getObjectsRoot();
        boolean allowed = Stream.of(configuredRoots.split(java.util.regex.Pattern.quote(File.pathSeparator)))
            .filter(value -> !value.isBlank())
            .map(value -> Path.of(value.trim()).toAbsolutePath().normalize())
            .anyMatch(objectsRoot::startsWith);
        if (!allowed)
        {
            throw new IllegalStateException(
                "安全附件目录未包含在 INFERENCE_ALLOWED_INPUT_ROOTS 中: " + objectsRoot);
        }
    }

    private static final class ObservedFile
    {
        private final long size;
        private final long modified;
        private final long firstStableAt;

        private ObservedFile(long size, long modified, long firstStableAt)
        {
            this.size = size;
            this.modified = modified;
            this.firstStableAt = firstStableAt;
        }
    }
}
