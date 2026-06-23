package com.ruoyi.sensor.web;

import java.math.BigDecimal;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.sensor.domain.entity.ModelReleaseEntity;
import com.ruoyi.sensor.mapper.ModelReleaseMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelReleaseControllerTest
{
    @Test
    void rejectsReleaseWithoutArtifactHash()
    {
        ModelReleaseController controller = new ModelReleaseController(mock(ModelReleaseMapper.class));
        ModelReleaseEntity release = baseRelease();
        release.setFileSha256("not-a-sha256");

        AjaxResult result = controller.create(release);

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("SHA-256"));
    }

    @Test
    void rejectsActivationBeforeQualityAndShadowGatesPass()
    {
        ModelReleaseMapper mapper = mock(ModelReleaseMapper.class);
        ModelReleaseEntity release = baseRelease();
        release.setPrecisionScore(new BigDecimal("0.89"));
        release.setRecallScore(new BigDecimal("0.95"));
        release.setSevereRecallScore(new BigDecimal("0.97"));
        release.setFalsePositivePerDeviceDay(new BigDecimal("0.5"));
        release.setShadowDays(14);
        release.setConfidenceThreshold(new BigDecimal("90"));
        release.setConsecutiveHits(3);
        when(mapper.selectById(1L)).thenReturn(release);

        AjaxResult result = new ModelReleaseController(mapper).activate(1L);

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("precision"));
    }

    private ModelReleaseEntity baseRelease()
    {
        ModelReleaseEntity release = new ModelReleaseEntity();
        release.setModelName("bearing-production");
        release.setModelType("bearing");
        release.setSemanticVersion("1.0.0");
        release.setFileSha256("a".repeat(64));
        release.setTrainingDataVersion("train-2026-01");
        release.setValidationDataVersion("validation-2026-01");
        release.setThresholdVersion("threshold-1");
        return release;
    }
}
