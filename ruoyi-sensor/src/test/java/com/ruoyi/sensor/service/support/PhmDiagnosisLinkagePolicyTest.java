package com.ruoyi.sensor.service.support;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PhmDiagnosisLinkagePolicyTest
{
    @Test
    void mapsDiagnosisRiskAndHealthWithoutFalseNormalisation()
    {
        assertFalse(PhmDiagnosisLinkagePolicy.isAbnormalDiagnosis("正常", "低", "normal"), "normal diagnosis should not alarm");
        assertTrue(PhmDiagnosisLinkagePolicy.isAbnormalDiagnosis("轴承外圈故障", "低", "normal"), "fault diagnosis should alarm");
        assertTrue(PhmDiagnosisLinkagePolicy.isAbnormalDiagnosis("正常", "高", "normal"), "high risk should alarm");
        assertTrue(PhmDiagnosisLinkagePolicy.isAbnormalDiagnosis("正常", "低", "danger"), "danger alarm level should alarm");

        assertEquals(4, PhmDiagnosisLinkagePolicy.diagnosisAlarmLevel("高", "normal", 88), "high risk maps to level4");
        assertEquals(4, PhmDiagnosisLinkagePolicy.diagnosisAlarmLevel("低", "normal", 35), "low health maps to level4");
        assertEquals(2, PhmDiagnosisLinkagePolicy.diagnosisAlarmLevel("中", "normal", 85), "medium risk maps to level2");
        assertEquals(1, PhmDiagnosisLinkagePolicy.diagnosisAlarmLevel("低", "normal", 90), "low risk maps to level1");

        assertEquals("高", PhmDiagnosisLinkagePolicy.normalizeRiskLevel("high"));
        assertEquals("中", PhmDiagnosisLinkagePolicy.normalizeRiskLevel("medium"));
        assertEquals("低", PhmDiagnosisLinkagePolicy.normalizeRiskLevel("low"));
        assertTrue(PhmDiagnosisLinkagePolicy.isAbnormalDiagnosis("normal", "high", "normal"));
        assertEquals(4, PhmDiagnosisLinkagePolicy.diagnosisAlarmLevel("high", "normal", 90));
        assertEquals(2, PhmDiagnosisLinkagePolicy.diagnosisAlarmLevel("medium", "normal", 90));

        assertEquals(100, PhmDiagnosisLinkagePolicy.normalizeHealthIndex(120, 80), "health index upper bound");
        assertEquals(0, PhmDiagnosisLinkagePolicy.normalizeHealthIndex(-5, 80), "health index lower bound");
        assertEquals(80, PhmDiagnosisLinkagePolicy.normalizeHealthIndex(null, 80), "health index fallback");
    }

}
