package com.ruoyi.sensor.web;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.sensor.domain.entity.PhmAttachmentEntity;
import com.ruoyi.sensor.domain.entity.ModelReleaseEntity;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.mapper.ModelReleaseMapper;
import com.ruoyi.sensor.service.PhmService;
import com.ruoyi.sensor.service.PhmDataScopeService;
import com.ruoyi.sensor.service.PhmAttachmentStorageService;
import com.ruoyi.sensor.service.SensorWebSocketPushService;
import com.ruoyi.sensor.service.VibrationAnalysisBatchService;
import com.ruoyi.sensor.service.timeseries.TimeSeriesAnalysisService;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

class VibrationDiagnosisControllerTest
{
    @Test
    void inferenceFailureDoesNotCreatePhmAlarmLinkage()
    {
        TimeSeriesAnalysisService timeSeries = mock(TimeSeriesAnalysisService.class);
        VibrationAnalysisBatchService batches = mock(VibrationAnalysisBatchService.class);
        SensorWebSocketPushService push = mock(SensorWebSocketPushService.class);
        PhmService phm = mock(PhmService.class);

        VibrationDiagnosisController controller = new VibrationDiagnosisController(
                timeSeries, batches, push, phm,
                mock(PhmAttachmentStorageService.class),
                "http://127.0.0.1:1/infer", "http://127.0.0.1:1/infer",
                "test-internal-token-at-least-32-bytes", "DEV-001", "gear", 50, 50);

        controller.receiverAnalyze(Map.of("deviceCode", "DEV-001", "modelType", "gear"));

        verify(phm, never()).syncDiagnosisResult(any());
    }

    @Test
    void emptyInferencePayloadIsRejectedBeforePhmLinkage() throws Exception
    {
        VibrationDiagnosisController controller = new VibrationDiagnosisController(
                mock(TimeSeriesAnalysisService.class), mock(VibrationAnalysisBatchService.class),
                mock(SensorWebSocketPushService.class),
                mock(PhmService.class), mock(PhmAttachmentStorageService.class),
                "", "", "test-internal-token-at-least-32-bytes",
                "", "gear", 50, 50);
        Method validator = VibrationDiagnosisController.class
                .getDeclaredMethod("validatePythonResult", Map.class);
        validator.setAccessible(true);

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> validator.invoke(controller, Map.of()));

        assertTrue(error.getCause().getMessage().contains("空结果"));
    }

    @Test
    void arbitraryServerFilePathCannotCreateDiagnosisTask()
    {
        VibrationDiagnosisController controller = new VibrationDiagnosisController(
                mock(TimeSeriesAnalysisService.class), mock(VibrationAnalysisBatchService.class),
                mock(SensorWebSocketPushService.class),
                mock(PhmService.class), mock(PhmAttachmentStorageService.class),
                "", "", "test-internal-token-at-least-32-bytes",
                "", "gear", 50, 50);

        Map<String, Object> response = controller.createTask(Map.of(
            "deviceCode", "DEV-001",
            "filePath", "C:\\sensitive\\secret.mat"));

        assertEquals(500, response.get("code"));
        assertTrue(String.valueOf(response.get("msg")).contains("attachmentId"));
    }

    @Test
    void diagnosisPointMustBelongToDeviceAndSuppliesTrustedChannel() throws Exception
    {
        PhmService phm = mock(PhmService.class);
        PhmDeviceEntity device = new PhmDeviceEntity();
        device.setId(10L);
        device.setDeviceCode("DEV-010");
        PhmMeasurePointEntity point = new PhmMeasurePointEntity();
        point.setId(20L);
        point.setDeviceId(10L);
        point.setDeviceCode("DEV-010");
        point.setSignalType("vibration");
        point.setEnabled(true);
        point.setChannelId(7);
        when(phm.listMeasurePoints(10L)).thenReturn(List.of(point));

        VibrationDiagnosisController controller = controllerWith(phm);
        Method resolver = VibrationDiagnosisController.class.getDeclaredMethod(
            "resolveDiagnosisPoint", PhmDeviceEntity.class, Long.class);
        resolver.setAccessible(true);

        PhmMeasurePointEntity resolved = (PhmMeasurePointEntity) resolver.invoke(controller, device, 20L);
        assertEquals(7, resolved.getChannelId());

        InvocationTargetException mismatch = assertThrows(InvocationTargetException.class,
            () -> resolver.invoke(controller, device, 99L));
        assertTrue(mismatch.getCause().getMessage().contains("不属于所选设备"));
    }

    @Test
    void requestedExecutableModelVersionIsResolvedFromRegistry() throws Exception
    {
        ModelReleaseEntity release = new ModelReleaseEntity();
        release.setModelType("gear");
        release.setSemanticVersion("gear-2.1.0");
        release.setStatus("RETIRED");
        release.setArtifactUri("gear/gear-2.1.0.pth");
        release.setFileSha256("a".repeat(64));
        ModelReleaseMapper mapper = mock(ModelReleaseMapper.class);
        when(mapper.selectOne(any())).thenReturn(release);

        VibrationDiagnosisController controller = controllerWith(mock(PhmService.class));
        setField(controller, "modelReleaseMapper", mapper);
        Method resolver = VibrationDiagnosisController.class.getDeclaredMethod(
            "resolveModel", String.class, String.class);
        resolver.setAccessible(true);
        Object resolved = resolver.invoke(controller, "gear", "gear-2.1.0");
        Field version = resolved.getClass().getDeclaredField("modelVersion");
        version.setAccessible(true);
        assertEquals("gear-2.1.0", version.get(resolved));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosisOptionsRespectDeviceScopeAndDoNotExposeArtifacts() throws Exception
    {
        PhmDeviceEntity accessible = new PhmDeviceEntity();
        accessible.setId(10L);
        accessible.setDeviceCode("DEV-010");
        accessible.setDeviceName("授权设备");

        PhmMeasurePointEntity vibration = point(20L, 10L, "vibration", 7);
        PhmMeasurePointEntity temperature = point(21L, 10L, "temperature", 8);
        PhmMeasurePointEntity missingType = point(22L, 10L, null, 9);
        PhmMeasurePointEntity otherDevice = point(23L, 99L, "vibration", 10);

        ModelReleaseEntity release = new ModelReleaseEntity();
        release.setId(1L);
        release.setModelName("gear-production");
        release.setModelType("gear");
        release.setSemanticVersion("2.0.0");
        release.setStatus("VALIDATED");
        release.setArtifactUri("gear/2.0.0/model.pth");
        release.setFileSha256("a".repeat(64));

        PhmService phm = mock(PhmService.class);
        when(phm.listMeasurePoints(null)).thenReturn(List.of(vibration, temperature, missingType, otherDevice));
        PhmDataScopeService scope = mock(PhmDataScopeService.class);
        when(scope.listDevices(any())).thenReturn(List.of(accessible));
        ModelReleaseMapper mapper = mock(ModelReleaseMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(release));

        VibrationDiagnosisController controller = controllerWith(phm);
        setField(controller, "dataScopeService", scope);
        setField(controller, "modelReleaseMapper", mapper);
        Map<String, Object> response = controller.diagnosisOptions();
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        List<Map<String, Object>> points = (List<Map<String, Object>>) data.get("points");
        List<Map<String, Object>> versions = (List<Map<String, Object>>) data.get("modelVersions");

        assertEquals(1, ((List<?>) data.get("devices")).size());
        assertEquals(1, points.size());
        assertEquals(20L, points.get(0).get("id"));
        Map<String, Object> registered = versions.stream()
            .filter(item -> "2.0.0".equals(item.get("semanticVersion")))
            .findFirst().orElseThrow();
        assertFalse(registered.containsKey("artifactUri"));
        assertFalse(registered.containsKey("fileSha256"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void browserUploadStoresAttachmentWithoutWaitingForInference() throws Exception
    {
        PhmDeviceEntity device = new PhmDeviceEntity();
        device.setId(10L);
        device.setDeviceCode("DEV-010");
        PhmMeasurePointEntity point = point(20L, 10L, "vibration", 7);
        PhmService phm = mock(PhmService.class);
        when(phm.listMeasurePoints(10L)).thenReturn(List.of(point));
        PhmDataScopeService scope = mock(PhmDataScopeService.class);
        when(scope.getDevice(any())).thenReturn(device);
        PhmAttachmentStorageService storage = mock(PhmAttachmentStorageService.class);
        PhmAttachmentEntity attachment = new PhmAttachmentEntity();
        attachment.setId(88L);
        attachment.setFileName("sample.npy");
        attachment.setFileSize(64L);
        attachment.setSha256("a".repeat(64));
        when(storage.storeDiagnosisInput(any(), any(), any(), any(), any(), any())).thenReturn(attachment);
        VibrationDiagnosisController controller = new VibrationDiagnosisController(
            mock(TimeSeriesAnalysisService.class), mock(VibrationAnalysisBatchService.class),
            mock(SensorWebSocketPushService.class), phm, storage,
            "", "", "test-internal-token-at-least-32-bytes", "", "gear", 50, 50);
        setField(controller, "dataScopeService", scope);
        MockMultipartFile file = new MockMultipartFile("file", "sample.npy",
            "application/octet-stream", new byte[] {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'});

        Map<String, Object> response;
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class))
        {
            security.when(SecurityUtils::getUsername).thenReturn("tester");
            response = controller.inferenceUpload(file, "gear", "1.0.0", "DEV-010", 7, 20L);
        }

        assertEquals(200, response.get("code"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals(88L, data.get("attachmentId"));
        assertEquals("sample.npy", data.get("filename"));
        verify(phm, never()).syncDiagnosisResult(any());
    }

    private PhmMeasurePointEntity point(Long id, Long deviceId, String signalType, Integer channelId)
    {
        PhmMeasurePointEntity point = new PhmMeasurePointEntity();
        point.setId(id);
        point.setDeviceId(deviceId);
        point.setPointName("测点-" + id);
        point.setSignalType(signalType);
        point.setEnabled(true);
        point.setChannelId(channelId);
        return point;
    }

    private VibrationDiagnosisController controllerWith(PhmService phm)
    {
        return new VibrationDiagnosisController(
            mock(TimeSeriesAnalysisService.class), mock(VibrationAnalysisBatchService.class),
            mock(SensorWebSocketPushService.class), phm,
            mock(PhmAttachmentStorageService.class), "", "",
            "test-internal-token-at-least-32-bytes", "", "gear", 50, 50);
    }

    private void setField(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
