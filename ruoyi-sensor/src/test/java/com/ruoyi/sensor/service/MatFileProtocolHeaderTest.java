package com.ruoyi.sensor.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import com.ruoyi.sensor.domain.dto.MatFileProtocolHeader;

class MatFileProtocolHeaderTest
{
    @Test
    void acceptsValidV2Metadata()
    {
        MatFileProtocolHeader header = valid();
        assertDoesNotThrow(() -> header.validate(128L * 1024 * 1024));
    }

    @Test
    void rejectsUnsafeFilenameAndInvalidTimestamp()
    {
        MatFileProtocolHeader header = valid();
        header.setFilename("../secret.mat");
        final MatFileProtocolHeader unsafeName = header;
        assertThrows(IllegalArgumentException.class, () -> unsafeName.validate(128L * 1024 * 1024));

        header = valid();
        header.setAcquisitionTime("2026-08-16 10:00:00");
        final MatFileProtocolHeader invalidTime = header;
        assertThrows(IllegalArgumentException.class, () -> invalidTime.validate(128L * 1024 * 1024));
    }

    @Test
    void rejectsOversizedOrInvalidHash()
    {
        MatFileProtocolHeader header = valid();
        header.setFilesize(128L * 1024 * 1024 + 1);
        final MatFileProtocolHeader oversized = header;
        assertThrows(IllegalArgumentException.class, () -> oversized.validate(128L * 1024 * 1024));

        header = valid();
        header.setSha256("bad");
        final MatFileProtocolHeader invalidHash = header;
        assertThrows(IllegalArgumentException.class, () -> invalidHash.validate(128L * 1024 * 1024));
    }

    private MatFileProtocolHeader valid()
    {
        MatFileProtocolHeader header = new MatFileProtocolHeader();
        header.setFilename("sample.mat");
        header.setFilesize(1024L);
        header.setSha256("a".repeat(64));
        header.setDeviceCode("DEV-001");
        header.setPointCode("P-01");
        header.setChannelId(1);
        header.setAcquisitionTime("2026-08-16T10:00:00+08:00");
        return header;
    }
}
