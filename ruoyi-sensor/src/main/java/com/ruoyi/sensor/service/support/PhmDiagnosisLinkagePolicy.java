package com.ruoyi.sensor.service.support;

public final class PhmDiagnosisLinkagePolicy
{
    private PhmDiagnosisLinkagePolicy()
    {
    }

    public static boolean isAbnormalDiagnosis(String diagnosisResult, String riskLevel, String alarmLevel)
    {
        String result = diagnosisResult == null ? "" : diagnosisResult;
        String risk = riskLevel == null ? "" : riskLevel;
        String level = alarmLevel == null ? "" : alarmLevel;
        return result.contains("故障") || result.contains("异常") || result.contains("失败")
                || "高".equals(risk) || "中".equals(risk)
                || level.startsWith("level") || "warning".equalsIgnoreCase(level) || "danger".equalsIgnoreCase(level);
    }

    public static int diagnosisAlarmLevel(String riskLevel, String alarmLevel, int healthIndex)
    {
        if ("高".equals(riskLevel) || "danger".equalsIgnoreCase(alarmLevel) || healthIndex < 40)
        {
            return 4;
        }
        if ("中".equals(riskLevel) || "warning".equalsIgnoreCase(alarmLevel) || healthIndex < 70)
        {
            return 2;
        }
        return 1;
    }

    public static int normalizeHealthIndex(Integer healthIndex, int fallback)
    {
        int value = healthIndex == null ? fallback : healthIndex;
        return Math.max(0, Math.min(100, value));
    }
}
