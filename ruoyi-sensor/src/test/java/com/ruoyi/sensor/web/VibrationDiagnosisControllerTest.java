package com.ruoyi.sensor.web;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.sensor.service.PhmService;
import com.ruoyi.sensor.service.SensorWebSocketPushService;
import com.ruoyi.sensor.service.VibrationAnalysisBatchService;
import com.ruoyi.sensor.service.VibrationAnalysisPersistenceService;
import com.ruoyi.sensor.service.timeseries.TimeSeriesAnalysisService;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class VibrationDiagnosisControllerTest
{
    @Test
    void inferenceFailureDoesNotCreatePhmAlarmLinkage()
    {
        TimeSeriesAnalysisService timeSeries = mock(TimeSeriesAnalysisService.class);
        VibrationAnalysisBatchService batches = mock(VibrationAnalysisBatchService.class);
        VibrationAnalysisPersistenceService persistence = mock(VibrationAnalysisPersistenceService.class);
        SensorWebSocketPushService push = mock(SensorWebSocketPushService.class);
        PhmService phm = mock(PhmService.class);

        VibrationDiagnosisController controller = new VibrationDiagnosisController(
                timeSeries, batches, persistence, push, phm,
                "http://127.0.0.1:1/infer", "http://127.0.0.1:1/infer",
                "test-internal-token-at-least-32-bytes", "DEV-001", "gear", 50, 50);

        controller.receiverAnalyze(Map.of("deviceCode", "DEV-001", "modelType", "gear"));

        verify(persistence, never()).saveAsync(any());
        verify(phm, never()).syncDiagnosisResult(any());
    }

    @Test
    void emptyInferencePayloadIsRejectedBeforePhmLinkage() throws Exception
    {
        VibrationDiagnosisController controller = new VibrationDiagnosisController(
                mock(TimeSeriesAnalysisService.class), mock(VibrationAnalysisBatchService.class),
                mock(VibrationAnalysisPersistenceService.class), mock(SensorWebSocketPushService.class),
                mock(PhmService.class), "", "", "test-internal-token-at-least-32-bytes",
                "", "gear", 50, 50);
        Method validator = VibrationDiagnosisController.class
                .getDeclaredMethod("validatePythonResult", Map.class);
        validator.setAccessible(true);

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> validator.invoke(controller, Map.of()));

        assertTrue(error.getCause().getMessage().contains("空结果"));
    }
}
