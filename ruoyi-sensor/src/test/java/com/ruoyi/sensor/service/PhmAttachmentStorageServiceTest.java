package com.ruoyi.sensor.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.mapper.PhmAttachmentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class PhmAttachmentStorageServiceTest
{
    @TempDir
    Path tempDir;

    @Test
    void storesPdfWithRandomNameAndSha256() throws Exception
    {
        PhmAttachmentMapper mapper = mock(PhmAttachmentMapper.class);
        doAnswer(invocation -> {
            PhmAttachmentEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            return 1;
        }).when(mapper).insert(any(PhmAttachmentEntity.class));
        AttachmentVirusScanner scanner = new AttachmentVirusScanner(false, "", "", 30);
        PhmAttachmentStorageService service = new PhmAttachmentStorageService(
            tempDir.toString(), scanner, mapper, mock(PhmDataScopeService.class));
        MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "application/pdf",
            "%PDF-1.7\nsafe test".getBytes(StandardCharsets.US_ASCII));

        PhmAttachmentEntity entity = service.store(
            file, "REPORT", "report", null, "inspection", "tester");

        assertEquals(100L, entity.getId());
        assertEquals("SKIPPED", entity.getScanStatus());
        assertEquals(64, entity.getSha256().length());
        assertNotNull(service.content(entity));
    }

    @Test
    void rejectsExtensionWithForgedSignature()
    {
        PhmAttachmentStorageService service = new PhmAttachmentStorageService(
            tempDir.toString(), new AttachmentVirusScanner(false, "", "", 30),
            mock(PhmAttachmentMapper.class), mock(PhmDataScopeService.class));
        MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "application/pdf",
            "not a pdf".getBytes(StandardCharsets.US_ASCII));

        assertThrows(IllegalArgumentException.class,
            () -> service.store(file, "REPORT", "report", null, null, "tester"));
    }
}
