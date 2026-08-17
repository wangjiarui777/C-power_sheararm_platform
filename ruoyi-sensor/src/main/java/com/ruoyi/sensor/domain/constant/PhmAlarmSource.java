package com.ruoyi.sensor.domain.constant;

/**
 * Sources of PHM alarm events.  The source is deliberately independent from
 * alarm type so that business rules and model diagnosis share one workflow.
 */
public final class PhmAlarmSource
{
    public static final String BUSINESS = "BUSINESS";
    public static final String MODEL = "MODEL";

    private PhmAlarmSource()
    {
    }

    public static boolean isValid(String source)
    {
        return BUSINESS.equalsIgnoreCase(source) || MODEL.equalsIgnoreCase(source);
    }

    public static String normalize(String source)
    {
        if (!isValid(source))
        {
            return null;
        }
        return MODEL.equalsIgnoreCase(source) ? MODEL : BUSINESS;
    }
}
