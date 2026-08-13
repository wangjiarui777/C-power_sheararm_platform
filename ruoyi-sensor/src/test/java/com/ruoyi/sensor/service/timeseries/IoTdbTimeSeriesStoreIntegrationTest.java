package com.ruoyi.sensor.service.timeseries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.domain.dto.VibrationFrameEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

@EnabledIfEnvironmentVariable(named = "IOTDB_INTEGRATION_TEST", matches = "true")
class IoTdbTimeSeriesStoreIntegrationTest
{
    @Test
    void createsSchemaAndRoundTripsTelemetryAndVibrationFrame() throws Exception
    {
        IoTdbTimeSeriesStore store = configuredStore();
        String deviceCode = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        Date sampleTime = new Date();
        try
        {
            store.init();
            waitUntilAvailable(store);
            assertEquals("AVAILABLE", store.getStatus().getState(),
                () -> "IoTDB connection failed: " + store.getStatus().getLastError());

            TelemetryEnvelope telemetry = new TelemetryEnvelope();
            telemetry.setDeviceCode(deviceCode);
            telemetry.setPointCode("CH-1");
            telemetry.setChannelId(1);
            telemetry.setMetricCode("vibration");
            telemetry.setSignalType("vibration");
            telemetry.setUnit("mm/s");
            telemetry.setValue(1.234D);
            telemetry.setQuality("GOOD");
            telemetry.setSampleTime(sampleTime);
            telemetry.setReceiveTime(new Date());
            telemetry.setSequence(sampleTime.getTime());
            assertTrue(store.writeTelemetry(telemetry));

            VibrationFrameEnvelope frame = new VibrationFrameEnvelope();
            frame.setDeviceCode(deviceCode);
            frame.setPointCode("CH-1");
            frame.setChannelId(1);
            frame.setAxis("radial");
            frame.setUnit("mm/s");
            frame.setSampleRate(4096);
            frame.setSampleCount(4);
            frame.setWaveform(List.of(0.1D, -0.2D, 0.3D, -0.4D));
            frame.setSpectrum(List.of(1.0D, 2.0D, 3.0D));
            frame.setFreqStep(10D);
            frame.setQuality("GOOD");
            frame.setSampleTime(sampleTime);
            frame.setReceiveTime(new Date());
            frame.setSequence(sampleTime.getTime());
            assertTrue(store.writeVibrationFrame(frame));

            DiagnosisResultSnapshot diagnosis = new DiagnosisResultSnapshot();
            diagnosis.setRecordId(Math.abs(UUID.randomUUID().getMostSignificantBits()));
            diagnosis.setDeviceCode(deviceCode);
            diagnosis.setPointId(1L);
            diagnosis.setChannelId(1);
            diagnosis.setAnalysisMode("gear");
            diagnosis.setModelVersion("v1.0.0");
            diagnosis.setDiagnosisResult("齿轮点蚀");
            diagnosis.setConfidence(new BigDecimal("98.25"));
            diagnosis.setHealthIndex(62);
            diagnosis.setRiskLevel("高");
            diagnosis.setEvidence("[{\"title\":\"频谱边带\"}]");
            diagnosis.setSampleTime(sampleTime);
            diagnosis.setCreateTime(new Date());
            diagnosis.setUpdateTime(new Date());
            assertTrue(store.writeDiagnosisResult(diagnosis));

            List<Map<String, Object>> trend = store.queryTelemetryTrend(
                deviceCode, "CH-1", "vibration",
                new Date(sampleTime.getTime() - 1000L), new Date(sampleTime.getTime() + 1000L), 10);
            assertFalse(trend.isEmpty());
            assertEquals(1.234D, ((Number) trend.get(trend.size() - 1).get("value")).doubleValue(), 0.000001D);

            VibrationFrameSnapshot loaded = store.loadLatestVibrationFrame(deviceCode, 1);
            assertNotNull(loaded);
            assertEquals(4096, loaded.getSampleRate());
            assertEquals(frame.getWaveform(), loaded.getWaveform());
            assertEquals(frame.getSpectrum(), loaded.getSpectrum());
            DiagnosisResultSnapshot loadedDiagnosis = store.loadLatestDiagnosis(deviceCode);
            assertNotNull(loadedDiagnosis);
            assertEquals(diagnosis.getRecordId(), loadedDiagnosis.getRecordId());
            assertEquals("齿轮点蚀", loadedDiagnosis.getDiagnosisResult());
            assertEquals(98.25D, loadedDiagnosis.getConfidence().doubleValue(), 0.000001D);
            assertNotNull(store.getStatus().getLastSuccessfulWriteTime());
            assertNotNull(store.getStatus().getLastSuccessfulOperationTime());
        }
        finally
        {
            store.destroy();
        }
    }

    private IoTdbTimeSeriesStore configuredStore()
    {
        IoTdbTimeSeriesStore store = new IoTdbTimeSeriesStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "database", "monitoring_it");
        ReflectionTestUtils.setField(store, "nodeUrls", "127.0.0.1:6667");
        ReflectionTestUtils.setField(store, "username", "root");
        ReflectionTestUtils.setField(store, "password", "root");
        ReflectionTestUtils.setField(store, "queryTimeoutMs", 5000L);
        ReflectionTestUtils.setField(store, "connectionTimeoutMs", 3000);
        ReflectionTestUtils.setField(store, "waitSessionTimeoutMs", 3000L);
        ReflectionTestUtils.setField(store, "maxRetryCount", 1);
        ReflectionTestUtils.setField(store, "retryIntervalMs", 200L);
        ReflectionTestUtils.setField(store, "reconnectIntervalSeconds", 30L);
        ReflectionTestUtils.setField(store, "rpcCompression", true);
        ReflectionTestUtils.setField(store, "redirection", true);
        ReflectionTestUtils.setField(store, "autoFetchNodes", true);
        ReflectionTestUtils.setField(store, "useSsl", false);
        ReflectionTestUtils.setField(store, "trustStore", "");
        ReflectionTestUtils.setField(store, "trustStorePassword", "");
        ReflectionTestUtils.setField(store, "fetchSize", 64);
        ReflectionTestUtils.setField(store, "sessionPoolSize", 2);
        ReflectionTestUtils.setField(store, "telemetryTtlDays", 1);
        ReflectionTestUtils.setField(store, "frameTtlDays", 1);
        ReflectionTestUtils.setField(store, "diagnosisTtlDays", 1);
        ReflectionTestUtils.setField(store, "timestampPrecision", "us");
        return store;
    }

    private void waitUntilAvailable(IoTdbTimeSeriesStore store) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 15000L;
        while (!store.getStatus().isAvailable() && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(100L);
        }
    }
}
