package com.ruoyi.sensor.domain.dto;

import java.time.OffsetDateTime;
import java.util.Locale;
import lombok.Data;

/** Metadata carried by the CWRU_MAT_V2 TCP file protocol. */
@Data
public class MatFileProtocolHeader
{
    private String filename;
    private Long filesize;
    private String sha256;
    private String deviceCode;
    private String pointCode;
    /** Physical acquisition channel number, not the database mapping id. */
    private Integer channelId;
    private String acquisitionTime;

    public OffsetDateTime parsedAcquisitionTime()
    {
        if (acquisitionTime == null || acquisitionTime.isBlank())
        {
            throw new IllegalArgumentException("缺少 acquisitionTime");
        }
        try
        {
            return OffsetDateTime.parse(acquisitionTime);
        }
        catch (RuntimeException ex)
        {
            throw new IllegalArgumentException("acquisitionTime 必须是带时区的 ISO-8601 时间", ex);
        }
    }

    public void validate(long maxFileSize)
    {
        if (filename == null || filename.isBlank() || filename.length() > 255
            || filename.contains("\\") || filename.contains("/")
            || !filename.toLowerCase(Locale.ROOT).endsWith(".mat"))
        {
            throw new IllegalArgumentException("filename 必须是安全的 .mat 文件名");
        }
        if (filesize == null || filesize <= 0 || filesize > maxFileSize)
        {
            throw new IllegalArgumentException("filesize 必须在 1 到 " + maxFileSize + " 字节之间");
        }
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}"))
        {
            throw new IllegalArgumentException("sha256 必须是 64 位十六进制字符串");
        }
        if (deviceCode == null || !deviceCode.matches("[A-Za-z0-9._-]{1,64}"))
        {
            throw new IllegalArgumentException("deviceCode 格式错误");
        }
        if (pointCode == null || !pointCode.matches("[A-Za-z0-9._-]{1,64}"))
        {
            throw new IllegalArgumentException("pointCode 格式错误");
        }
        if (channelId == null || channelId < 1 || channelId > 64)
        {
            throw new IllegalArgumentException("channelId 必须在 1 到 64 之间");
        }
        parsedAcquisitionTime();
    }
}
