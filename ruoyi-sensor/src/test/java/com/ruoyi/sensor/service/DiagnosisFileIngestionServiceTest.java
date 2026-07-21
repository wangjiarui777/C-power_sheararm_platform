package com.ruoyi.sensor.service;

import java.nio.file.Files;
import java.nio.file.Path;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.mapper.PhmDeviceMapper;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisFileIngestionServiceTest
{
    @TempDir
    Path tempDir;

    @Test
    void stableFileIsImportedAndRemovedFromInbox() throws Exception
    {
        Path inbox = tempDir.resolve("inbox");
        Path pointDirectory = Files.createDirectories(inbox.resolve("DEV-001").resolve("P-1"));
        Path source = pointDirectory.resolve("sample.npy");
        Files.write(source, new byte[] {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'});
        PhmAttachmentStorageService storage = mock(PhmAttachmentStorageService.class);
        Path objects = tempDir.resolve("attachments").resolve("objects").toAbsolutePath();
        when(storage.getObjectsRoot()).thenReturn(objects);
        PhmDeviceMapper devices = mock(PhmDeviceMapper.class);
        PhmDeviceEntity device = new PhmDeviceEntity();
        device.setId(1L);
        device.setDeviceCode("DEV-001");
        when(devices.selectOne(any())).thenReturn(device);
        PhmMeasurePointMapper points = mock(PhmMeasurePointMapper.class);
        PhmMeasurePointEntity point = new PhmMeasurePointEntity();
        point.setId(2L);
        point.setPointCode("P-1");
        point.setChannelId(3);
        when(points.selectOne(any())).thenReturn(point);
        DiagnosisFileIngestionService service = new DiagnosisFileIngestionService(
            inbox.toString(), 0, objects.toString(), storage, devices, points);

        service.scan();
        service.scan();

        verify(storage).importDiagnosisFile(eq(source.toAbsolutePath()), eq(1L), eq(2L), eq(3), eq("system-ingest"));
        assertFalse(Files.exists(source));
    }

    @Test
    void unknownDeviceFileIsMovedToRejectedDirectory() throws Exception
    {
        Path inbox = tempDir.resolve("unknown-inbox");
        Path source = Files.createDirectories(inbox.resolve("MISSING").resolve("P-1")).resolve("sample.mat");
        Files.writeString(source, "MATLAB test");
        PhmAttachmentStorageService storage = mock(PhmAttachmentStorageService.class);
        Path objects = tempDir.resolve("objects").toAbsolutePath();
        when(storage.getObjectsRoot()).thenReturn(objects);
        PhmDeviceMapper devices = mock(PhmDeviceMapper.class);
        DiagnosisFileIngestionService service = new DiagnosisFileIngestionService(
            inbox.toString(), 0, objects.toString(), storage, devices, mock(PhmMeasurePointMapper.class));

        service.scan();
        service.scan();

        assertFalse(Files.exists(source));
        assertTrue(Files.list(inbox.resolve("rejected").resolve("MISSING").resolve("P-1"))
            .anyMatch(path -> path.getFileName().toString().endsWith("sample.mat")));
        verify(storage, never()).importDiagnosisFile(any(), any(), any(), any(), any());
    }

    @Test
    void startupRejectsAttachmentRootOutsidePythonAllowList()
    {
        PhmAttachmentStorageService storage = mock(PhmAttachmentStorageService.class);
        when(storage.getObjectsRoot()).thenReturn(tempDir.resolve("attachments/objects").toAbsolutePath());

        assertThrows(IllegalStateException.class, () -> new DiagnosisFileIngestionService(
            tempDir.resolve("inbox-validation").toString(), 0,
            tempDir.resolve("different-root").toString(), storage, mock(PhmDeviceMapper.class),
            mock(PhmMeasurePointMapper.class)));
    }

    @Test
    void partialFileIsIgnored() throws Exception
    {
        Path inbox = tempDir.resolve("partial-inbox");
        Path source = Files.createDirectories(inbox.resolve("DEV-001").resolve("P-1")).resolve("sample.npy.part");
        Files.writeString(source, "still writing");
        PhmAttachmentStorageService storage = mock(PhmAttachmentStorageService.class);
        Path objects = tempDir.resolve("partial-objects").toAbsolutePath();
        when(storage.getObjectsRoot()).thenReturn(objects);
        DiagnosisFileIngestionService service = new DiagnosisFileIngestionService(
            inbox.toString(), 0, objects.toString(), storage, mock(PhmDeviceMapper.class),
            mock(PhmMeasurePointMapper.class));

        service.scan();
        service.scan();

        assertTrue(Files.exists(source));
        verify(storage, never()).importDiagnosisFile(any(), any(), any(), any(), any());
    }
}
