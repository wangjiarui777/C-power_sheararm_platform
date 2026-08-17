package com.ruoyi.sensor.service.support;

public final class PhmDiagnosisLinkagePolicy
{
    private PhmDiagnosisLinkagePolicy()
    {
    }

    public static boolean isAbnormalDiagnosis(String diagnosisResult, String riskLevel, String alarmLevel)
    {
        String result = diagnosisResult == null ? "" : diagnosisResult;
        String risk = normalizeRiskLevel(riskLevel);
        String level = alarmLevel == null ? "" : alarmLevel.trim().toLowerCase();
        return result.contains("故障") || result.contains("异常") || result.contains("失败")
                || "高".equals(risk) || "中".equals(risk)
                || level.startsWith("level") || "warning".equals(level) || "danger".equals(level);
    }

    public static int diagnosisAlarmLevel(String riskLevel, String alarmLevel, int healthIndex)
    {
        String risk = normalizeRiskLevel(riskLevel);
        String level = alarmLevel == null ? "" : alarmLevel.trim().toLowerCase();
        if ("高".equals(risk) || "danger".equals(level) || healthIndex < 40)
        {
            return 4;
        }
        if ("中".equals(risk) || "warning".equals(level) || healthIndex < 70)
        {
            return 2;
        }
        return 1;
    }

    /** Normalizes the model's Chinese and English risk labels to one format. */
    public static String normalizeRiskLevel(String riskLevel)
    {
        String value = riskLevel == null ? "" : riskLevel.trim().toLowerCase();
        if ("高".equals(value) || "high".equals(value)) return "高";
        if ("中".equals(value) || "medium".equals(value)) return "中";
        if ("低".equals(value) || "low".equals(value)) return "低";
        return riskLevel == null ? "" : riskLevel.trim();
    }

    public static int normalizeHealthIndex(Integer healthIndex, int fallback)
    {
        int value = healthIndex == null ? fallback : healthIndex;
        return Math.max(0, Math.min(100, value));
    }
}
