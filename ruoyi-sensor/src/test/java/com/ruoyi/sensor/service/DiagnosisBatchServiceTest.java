package com.ruoyi.sensor.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.ruoyi.sensor.domain.entity.DiagnosisBatchEntity;
import com.ruoyi.sensor.domain.entity.InferenceTaskEntity;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.mapper.DiagnosisBatchMapper;
import com.ruoyi.sensor.mapper.InferenceTaskMapper;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisBatchServiceTest
{
    private DiagnosisBatchMapper batchMapper;
    private InferenceTaskMapper taskMapper;
    private PhmMeasurePointMapper pointMapper;
    private PhmAttachmentStorageService attachmentStorage;
    private DiagnosisBatchService service;

    @BeforeEach
    void setUp()
    {
        batchMapper = mock(DiagnosisBatchMapper.class);
        taskMapper = mock(InferenceTaskMapper.class);
        pointMapper = mock(PhmMeasurePointMapper.class);
        attachmentStorage = mock(PhmAttachmentStorageService.class);
        service = new DiagnosisBatchService(batchMapper, taskMapper, pointMapper,
            attachmentStorage, mock(PhmDataScopeService.class), 8);
    }

    @Test
    void createsOneIndependentTaskForEachValidatedPoint()
    {
        PhmDeviceEntity device = device(10L, "DEV-001");
        PhmMeasurePointEntity point1 = point(101L, 10L, 1);
        PhmMeasurePointEntity point2 = point(102L, 10L, 2);
        when(pointMapper.selectById(101L)).thenReturn(point1);
        when(pointMapper.selectById(102L)).thenReturn(point2);
        when(attachmentStorage.getAccessibleDiagnosisInput(201L)).thenReturn(attachment(201L, 10L, 101L, 1));
        when(attachmentStorage.getAccessibleDiagnosisInput(202L)).thenReturn(attachment(202L, 10L, 102L, 2));
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(batchMapper.insert(any(DiagnosisBatchEntity.class))).thenAnswer(invocation -> {
            ((DiagnosisBatchEntity) invocation.getArgument(0)).setId(301L);
            return 1;
        });
        AtomicLong taskIds = new AtomicLong(400L);
        when(taskMapper.insert(any(InferenceTaskEntity.class))).thenAnswer(invocation -> {
            ((InferenceTaskEntity) invocation.getArgument(0)).setId(taskIds.incrementAndGet());
            return 1;
        });

        DiagnosisBatchService.BatchCreation created = service.create(device, "client-1", List.of(
                Map.of("pointId", 101L, "attachmentId", 201L),
                Map.of("pointId", 102L, "attachmentId", 202L)), Map.of(
                    101L, new DiagnosisBatchService.PointModel("gear", "gear-v1"),
                    102L, new DiagnosisBatchService.PointModel("bearing", "bearing-v2")), "tester");

        assertEquals(301L, created.getBatch().getId());
        assertEquals("PENDING", created.getBatch().getStatus());
        assertEquals(2, created.getTasks().size());
        assertTrue(created.getTasks().stream().allMatch(task -> task.getBatchId().equals(301L)));
        assertEquals(List.of(101L, 102L), created.getTasks().stream()
            .map(InferenceTaskEntity::getPointId).toList());
        assertEquals(List.of("gear", "bearing"), created.getTasks().stream()
            .map(InferenceTaskEntity::getModelType).toList());
        assertEquals(List.of("gear-v1", "bearing-v2"), created.getTasks().stream()
            .map(InferenceTaskEntity::getRequestedModelVersion).toList());
        verify(taskMapper, org.mockito.Mockito.times(2)).insert(any(InferenceTaskEntity.class));
    }

    @Test
    void rejectsAttachmentBoundToAnotherPointBeforeCreatingBatch()
    {
        PhmDeviceEntity device = device(10L, "DEV-001");
        when(pointMapper.selectById(101L)).thenReturn(point(101L, 10L, 1));
        when(attachmentStorage.getAccessibleDiagnosisInput(201L))
            .thenReturn(attachment(201L, 10L, 999L, 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.create(device, "client-2", List.of(Map.of("pointId", 101L, "attachmentId", 201L)),
                Map.of(101L, new DiagnosisBatchService.PointModel("bearing", "v1")), "tester"));

        assertTrue(error.getMessage().contains("未绑定所选测点"));
        verify(batchMapper, never()).insert(any(DiagnosisBatchEntity.class));
        verify(taskMapper, never()).insert(any(InferenceTaskEntity.class));
    }

    @Test
    void refreshMarksMixedTerminalResultsAsPartial()
    {
        DiagnosisBatchEntity batch = new DiagnosisBatchEntity();
        batch.setId(301L);
        batch.setStatus("RUNNING");
        batch.setTotalCount(2);
        InferenceTaskEntity succeeded = task(101L, "SUCCEEDED", 1);
        InferenceTaskEntity failed = task(102L, "FAILED", 1);
        when(batchMapper.selectById(301L)).thenReturn(batch);
        when(taskMapper.selectList(any())).thenReturn(List.of(succeeded, failed));

        DiagnosisBatchEntity refreshed = service.refresh(301L);

        assertEquals("PARTIAL", refreshed.getStatus());
        assertEquals(1, refreshed.getSuccessCount());
        assertEquals(1, refreshed.getFailedCount());
        verify(batchMapper).updateById(batch);
    }

    private PhmDeviceEntity device(Long id, String code)
    {
        PhmDeviceEntity device = new PhmDeviceEntity();
        device.setId(id);
        device.setDeviceCode(code);
        return device;
    }

    private PhmMeasurePointEntity point(Long id, Long deviceId, Integer channelId)
    {
        PhmMeasurePointEntity point = new PhmMeasurePointEntity();
        point.setId(id);
        point.setDeviceId(deviceId);
        point.setPointCode("P-" + id);
        point.setPointName("Point " + id);
        point.setSignalType("vibration");
        point.setChannelId(channelId);
        point.setEnabled(true);
        return point;
    }

    private PhmAttachmentEntity attachment(Long id, Long deviceId, Long pointId, Integer channelId)
    {
        PhmAttachmentEntity attachment = new PhmAttachmentEntity();
        attachment.setId(id);
        attachment.setBizId(deviceId);
        attachment.setPointId(pointId);
        attachment.setChannelId(channelId);
        attachment.setSha256("sha-" + id);
        return attachment;
    }

    private InferenceTaskEntity task(Long pointId, String status, int attempt)
    {
        InferenceTaskEntity task = new InferenceTaskEntity();
        task.setPointId(pointId);
        task.setStatus(status);
        task.setAttemptNo(attempt);
        return task;
    }
}
