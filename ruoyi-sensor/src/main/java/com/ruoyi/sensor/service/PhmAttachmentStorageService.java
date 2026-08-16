package com.ruoyi.sensor.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Comparator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.query.PhmDeviceScopeQuery;
import com.ruoyi.sensor.mapper.PhmAttachmentMapper;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PhmAttachmentStorageService
{
    private static final Map<String, Set<String>> PURPOSE_EXTENSIONS = Map.of(
        "DIAGNOSIS_INPUT", Set.of("mat", "npy"),
        "REPORT", Set.of("pdf"),
        "MORPHOLOGY", Set.of("png", "jpg", "jpeg", "webp"),
        "GENERIC_IMAGE", Set.of("png", "jpg", "jpeg", "webp"),
        "GENERIC_DOCUMENT", Set.of("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    );
    private static final Map<String, Long> PURPOSE_LIMITS = Map.of(
        "DIAGNOSIS_INPUT", 128L * 1024 * 1024,
        "REPORT", 20L * 1024 * 1024,
        "MORPHOLOGY", 10L * 1024 * 1024,
        "GENERIC_IMAGE", 10L * 1024 * 1024,
        "GENERIC_DOCUMENT", 20L * 1024 * 1024
    );

    private final Path root;
    private final AttachmentVirusScanner virusScanner;
    private final PhmAttachmentMapper mapper;
    private final PhmDataScopeService dataScopeService;

    public PhmAttachmentStorageService(
        @Value("${sensor.attachment.root:D:/ruoyi-secure/attachments}") String root,
        AttachmentVirusScanner virusScanner,
        PhmAttachmentMapper mapper,
        PhmDataScopeService dataScopeService)
    {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.virusScanner = virusScanner;
        this.mapper = mapper;
        this.dataScopeService = dataScopeService;
    }

    @Transactional(rollbackFor = Exception.class)
    public PhmAttachmentEntity store(MultipartFile file, String purpose, String bizType, Long bizId,
        String reportType, String username) throws Exception
    {
        return store(file, purpose, bizType, bizId, null, null, reportType, username, true, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public PhmAttachmentEntity storeGeneric(MultipartFile file, String username) throws Exception
    {
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extension(originalName);
        String purpose = PURPOSE_EXTENSIONS.get("GENERIC_IMAGE").contains(extension)
            ? "GENERIC_IMAGE" : "GENERIC_DOCUMENT";
        return store(file, purpose, "user", SecurityUtils.getUserId(), null, null,
            null, username, false, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public PhmAttachmentEntity storeDiagnosisInput(MultipartFile file, Long deviceId, Long pointId,
        Integer channelId, String modelType, String username) throws Exception
    {
        if (pointId == null || channelId == null)
        {
            throw new IllegalArgumentException("诊断输入必须绑定测点和通道");
        }
        return store(file, "DIAGNOSIS_INPUT", "device", deviceId, pointId, channelId,
            modelType, username, true, null);
    }

    /**
     * Imports a file discovered in the trusted server-side diagnosis inbox. The
     * same validation, quarantine and object storage pipeline as browser uploads
     * is used, while device authorization is performed by the ingestion service.
     */
    @Transactional(rollbackFor = Exception.class)
    public PhmAttachmentEntity importDiagnosisFile(Path source, Long deviceId, String username) throws Exception
    {
        return importDiagnosisFile(source, deviceId, null, null, username);
    }

    @Transactional(rollbackFor = Exception.class)
    public PhmAttachmentEntity importDiagnosisFile(Path source, Long deviceId, Long pointId,
        Integer channelId, String username) throws Exception
    {
        Path normalized = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        {
            throw new IllegalArgumentException("诊断接入文件不存在或不是普通文件");
        }
        String extension = extension(normalized.getFileName().toString());
        String mimeType = "npy".equals(extension) ? "application/x-numpy" : "application/x-matlab-data";
        MultipartFile file = new PathMultipartFile(normalized, mimeType);
        return store(file, "DIAGNOSIS_INPUT", "device", deviceId, pointId, channelId, null, username, false,
            "SOURCE:SERVER_DIRECTORY");
    }

    private PhmAttachmentEntity store(MultipartFile file, String purpose, String bizType, Long bizId,
        Long pointId, Integer channelId, String reportType, String username,
        boolean enforceDeviceScope, String remark) throws Exception
    {
        String normalizedPurpose = purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = PURPOSE_EXTENSIONS.get(normalizedPurpose);
        if (allowed == null)
        {
            throw new IllegalArgumentException("不支持的附件用途");
        }
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extension(originalName);
        if (!allowed.contains(extension))
        {
            throw new IllegalArgumentException("附件扩展名与用途不匹配");
        }
        long size = file.getSize();
        if (size <= 0 || size > PURPOSE_LIMITS.get(normalizedPurpose))
        {
            throw new IllegalArgumentException("附件大小超出限制");
        }
        validateMimeType(normalizedPurpose, extension, file.getContentType());
        byte[] header = readHeader(file, 16);
        validateSignature(normalizedPurpose, extension, header);
        if (enforceDeviceScope && bizId != null && !"REPORT".equals(normalizedPurpose) && !canAccessDevice(bizId))
        {
            throw new SecurityException("无权访问附件所属设备");
        }

        Files.createDirectories(root.resolve("quarantine"));
        Files.createDirectories(root.resolve("objects"));
        String objectName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path quarantine = safeResolve(root.resolve("quarantine"), objectName);
        Path target = safeResolve(root.resolve("objects"), objectName);
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream();
                 java.io.OutputStream output = Files.newOutputStream(quarantine))
            {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0)
                {
                    if (read > 0)
                    {
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
            }
            String scanStatus = virusScanner.scan(quarantine);
            try
            {
                Files.move(quarantine, target, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored)
            {
                Files.move(quarantine, target, StandardCopyOption.REPLACE_EXISTING);
            }

            PhmAttachmentEntity entity = new PhmAttachmentEntity();
            entity.setBizType(normalizeBizType(normalizedPurpose, bizType));
            entity.setBizId(bizId);
            entity.setPointId(pointId);
            entity.setChannelId(channelId);
            entity.setFileName(originalName);
            entity.setFileUrl(null);
            entity.setObjectName(objectName);
            entity.setStoragePath(target.toString());
            entity.setFileExt(extension);
            entity.setMimeType(file.getContentType());
            entity.setFileSize(size);
            entity.setSha256(HexFormat.of().formatHex(digest.digest()));
            entity.setScanStatus(scanStatus);
            entity.setPurpose(normalizedPurpose);
            entity.setReportType(reportType);
            entity.setUploadBy(username);
            entity.setCreateTime(new Date());
            entity.setRemark(remark);
            mapper.insert(entity);
            return entity;
        }
        catch (Exception ex)
        {
            Files.deleteIfExists(quarantine);
            Files.deleteIfExists(target);
            throw ex;
        }
    }

    public PhmAttachmentEntity getAccessible(Long id)
    {
        PhmAttachmentEntity entity = mapper.selectById(id);
        if (entity == null)
        {
            return null;
        }
        if (!"REPORT".equals(entity.getPurpose()) && entity.getBizId() != null && !canAccessDevice(entity.getBizId()))
        {
            return null;
        }
        return entity;
    }

    public PhmAttachmentEntity getAccessibleDiagnosisInput(Long id)
    {
        PhmAttachmentEntity entity = getAccessible(id);
        return entity != null && "DIAGNOSIS_INPUT".equals(entity.getPurpose()) ? entity : null;
    }

    public PhmAttachmentEntity getAccessibleGeneric(Long id)
    {
        PhmAttachmentEntity entity = mapper.selectById(id);
        if (entity == null || entity.getPurpose() == null || !entity.getPurpose().startsWith("GENERIC_"))
        {
            return null;
        }
        String username = SecurityUtils.getUsername();
        return SecurityUtils.isAdmin() || username.equals(entity.getUploadBy()) ? entity : null;
    }

    /**
     * Resolves an attachment already authorized when a diagnosis task was created.
     * The immutable SHA-256 binding prevents a queued task from being redirected
     * to a different stored object while running outside the request security context.
     */
    public PhmAttachmentEntity getDiagnosisInputForTask(Long id, String expectedSha256)
    {
        PhmAttachmentEntity entity = mapper.selectById(id);
        if (entity == null || !"DIAGNOSIS_INPUT".equals(entity.getPurpose())
            || expectedSha256 == null || !expectedSha256.equalsIgnoreCase(entity.getSha256()))
        {
            return null;
        }
        return entity;
    }

    public List<PhmAttachmentEntity> listAccessibleDiagnosisInputs()
    {
        return mapper.selectList(new LambdaQueryWrapper<PhmAttachmentEntity>()
                .eq(PhmAttachmentEntity::getPurpose, "DIAGNOSIS_INPUT")
                .orderByDesc(PhmAttachmentEntity::getCreateTime))
            .stream()
            .filter(entity -> entity.getBizId() == null || canAccessDevice(entity.getBizId()))
            .sorted(Comparator.comparing(PhmAttachmentEntity::getCreateTime,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    public List<PhmAttachmentEntity> listAccessibleDiagnosisInputsForDevice(Long deviceId)
    {
        if (deviceId == null || !canAccessDevice(deviceId))
        {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<PhmAttachmentEntity>()
                .eq(PhmAttachmentEntity::getPurpose, "DIAGNOSIS_INPUT")
                .eq(PhmAttachmentEntity::getBizId, deviceId)
                .orderByDesc(PhmAttachmentEntity::getCreateTime));
    }

    public List<PhmAttachmentEntity> listAccessibleDiagnosisInputsForPoint(Long deviceId, Long pointId)
    {
        if (deviceId == null || pointId == null || !canAccessDevice(deviceId))
        {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<PhmAttachmentEntity>()
            .eq(PhmAttachmentEntity::getPurpose, "DIAGNOSIS_INPUT")
            .eq(PhmAttachmentEntity::getBizId, deviceId)
            .eq(PhmAttachmentEntity::getPointId, pointId)
            .orderByDesc(PhmAttachmentEntity::getCreateTime));
    }

    public PhmAttachmentEntity findDiagnosisInputByDeviceAndSha256(Long deviceId, String sha256)
    {
        return findDiagnosisInputByDevicePointAndSha256(deviceId, null, sha256);
    }

    public PhmAttachmentEntity findDiagnosisInputByDevicePointAndSha256(Long deviceId, Long pointId, String sha256)
    {
        if (deviceId == null || sha256 == null || sha256.isBlank())
        {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<PhmAttachmentEntity>()
            .eq(PhmAttachmentEntity::getPurpose, "DIAGNOSIS_INPUT")
            .eq(PhmAttachmentEntity::getBizId, deviceId)
            .eq(pointId != null, PhmAttachmentEntity::getPointId, pointId)
            .isNull(pointId == null, PhmAttachmentEntity::getPointId)
            .eq(PhmAttachmentEntity::getSha256, sha256)
            .last("LIMIT 1"));
    }

    public Path getObjectsRoot()
    {
        return root.resolve("objects").toAbsolutePath().normalize();
    }

    public Path trustedContentPath(PhmAttachmentEntity entity) throws IOException
    {
        return content(entity).getFile().toPath().toAbsolutePath().normalize();
    }

    public FileSystemResource content(PhmAttachmentEntity entity) throws IOException
    {
        if (entity == null || entity.getStoragePath() == null
            || (!"CLEAN".equals(entity.getScanStatus()) && !"SKIPPED".equals(entity.getScanStatus())))
        {
            throw new IOException("附件不可下载");
        }
        Path path = Path.of(entity.getStoragePath()).toAbsolutePath().normalize();
        if (!path.startsWith(root.resolve("objects").normalize()) || !Files.isRegularFile(path))
        {
            throw new IOException("附件文件不存在");
        }
        return new FileSystemResource(path);
    }

    public int delete(Long id) throws IOException
    {
        PhmAttachmentEntity entity = getAccessible(id);
        if (entity == null)
        {
            return 0;
        }
        int rows = mapper.deleteById(id);
        if (rows > 0 && entity.getStoragePath() != null)
        {
            Files.deleteIfExists(Path.of(entity.getStoragePath()));
        }
        return rows;
    }

    public int deleteGeneric(Long id) throws IOException
    {
        PhmAttachmentEntity entity = getAccessibleGeneric(id);
        if (entity == null)
        {
            return 0;
        }
        int rows = mapper.deleteById(id);
        if (rows > 0 && entity.getStoragePath() != null)
        {
            Files.deleteIfExists(Path.of(entity.getStoragePath()));
        }
        return rows;
    }

    private boolean canAccessDevice(Long deviceId)
    {
        PhmDeviceScopeQuery query = new PhmDeviceScopeQuery();
        query.setDeviceId(deviceId);
        return dataScopeService.getDevice(query) != null;
    }

    private String normalizeBizType(String purpose, String bizType)
    {
        if ("REPORT".equals(purpose))
        {
            return "report";
        }
        return bizType == null || bizType.isBlank() ? "device" : bizType.trim();
    }

    private Path safeResolve(Path directory, String objectName)
    {
        Path resolved = directory.resolve(objectName).toAbsolutePath().normalize();
        if (!resolved.startsWith(directory.toAbsolutePath().normalize()))
        {
            throw new IllegalArgumentException("非法对象路径");
        }
        return resolved;
    }

    private String safeOriginalName(String original)
    {
        String name = original == null ? "" : Path.of(original).getFileName().toString();
        if (name.isBlank() || name.length() > 255)
        {
            throw new IllegalArgumentException("文件名无效");
        }
        return name;
    }

    private String extension(String name)
    {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private byte[] readHeader(MultipartFile file, int length) throws IOException
    {
        try (InputStream input = file.getInputStream())
        {
            return input.readNBytes(length);
        }
    }

    private void validateSignature(String purpose, String extension, byte[] header)
    {
        boolean valid;
        if ("REPORT".equals(purpose) || "GENERIC_DOCUMENT".equals(purpose))
        {
            valid = switch (extension)
            {
                case "pdf" -> startsWith(header, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                case "docx", "xlsx", "pptx" -> startsWith(header, new byte[] {'P', 'K', 3, 4});
                case "doc", "xls", "ppt" -> startsWith(header,
                    new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1});
                case "txt" -> !containsNul(header);
                default -> false;
            };
        }
        else if ("DIAGNOSIS_INPUT".equals(purpose))
        {
            valid = "npy".equals(extension)
                ? startsWith(header, new byte[] {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'})
                : startsWith(header, "MATLAB".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    || startsWith(header, new byte[] {(byte) 0x89, 'H', 'D', 'F', '\r', '\n', 0x1a, '\n'});
        }
        else
        {
            valid = startsWith(header, new byte[] {(byte) 0x89, 'P', 'N', 'G'})
                || startsWith(header, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})
                || (header.length >= 12
                    && "RIFF".equals(new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                    && "WEBP".equals(new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)));
        }
        if (!valid)
        {
            throw new IllegalArgumentException("文件签名与声明用途不匹配");
        }
    }

    private void validateMimeType(String purpose, String extension, String mimeType)
    {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        boolean valid;
        if ("REPORT".equals(purpose))
        {
            valid = "application/pdf".equals(mime);
        }
        else if ("GENERIC_DOCUMENT".equals(purpose))
        {
            valid = switch (extension)
            {
                case "pdf" -> "application/pdf".equals(mime);
                case "txt" -> Set.of("text/plain", "application/octet-stream").contains(mime);
                case "doc" -> Set.of("application/msword", "application/octet-stream").contains(mime);
                case "xls" -> Set.of("application/vnd.ms-excel", "application/octet-stream").contains(mime);
                case "ppt" -> Set.of("application/vnd.ms-powerpoint", "application/octet-stream").contains(mime);
                case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime);
                case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mime);
                case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(mime);
                default -> false;
            };
        }
        else if ("DIAGNOSIS_INPUT".equals(purpose))
        {
            valid = Set.of("application/octet-stream", "application/x-numpy",
                "application/x-matlab-data", "application/matlab-mat").contains(mime);
        }
        else if ("png".equals(extension))
        {
            valid = "image/png".equals(mime);
        }
        else if ("webp".equals(extension))
        {
            valid = "image/webp".equals(mime);
        }
        else
        {
            valid = Set.of("image/jpeg", "image/jpg").contains(mime);
        }
        if (!valid)
        {
            throw new IllegalArgumentException("文件 MIME 类型与声明用途不匹配");
        }
    }

    private boolean startsWith(byte[] source, byte[] prefix)
    {
        if (source.length < prefix.length)
        {
            return false;
        }
        for (int i = 0; i < prefix.length; i++)
        {
            if (source[i] != prefix[i])
            {
                return false;
            }
        }
        return true;
    }

    private boolean containsNul(byte[] source)
    {
        for (byte value : source)
        {
            if (value == 0)
            {
                return true;
            }
        }
        return false;
    }

    private static final class PathMultipartFile implements MultipartFile
    {
        private final Path path;
        private final String contentType;

        private PathMultipartFile(Path path, String contentType)
        {
            this.path = path;
            this.contentType = contentType;
        }

        @Override
        public String getName()
        {
            return "file";
        }

        @Override
        public String getOriginalFilename()
        {
            return path.getFileName().toString();
        }

        @Override
        public String getContentType()
        {
            return contentType;
        }

        @Override
        public boolean isEmpty()
        {
            return getSize() == 0;
        }

        @Override
        public long getSize()
        {
            try
            {
                return Files.size(path);
            }
            catch (IOException ex)
            {
                return 0;
            }
        }

        @Override
        public byte[] getBytes() throws IOException
        {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(File destination) throws IOException
        {
            Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
