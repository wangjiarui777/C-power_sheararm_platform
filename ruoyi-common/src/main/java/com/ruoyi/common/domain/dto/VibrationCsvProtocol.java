package com.ruoyi.common.domain.dto;

import java.util.Arrays;

public class VibrationCsvProtocol
{
    public static final int FIELD_COUNT = 9;

    private final String header;
    private final String version;
    private final String globals;
    private final Double deTime;
    private final Double sampleRate;
    private final Double rpm;
    private final Double load;
    private final String faultType;
    private final Double faultSize;

    public VibrationCsvProtocol(String header, String version, String globals, Double deTime, Double sampleRate,
                                Double rpm, Double load, String faultType, Double faultSize)
    {
        this.header = header;
        this.version = version;
        this.globals = globals;
        this.deTime = deTime;
        this.sampleRate = sampleRate;
        this.rpm = rpm;
        this.load = load;
        this.faultType = faultType;
        this.faultSize = faultSize;
    }

    public String getHeader()
    {
        return header;
    }

    public String getVersion()
    {
        return version;
    }

    public String getGlobals()
    {
        return globals;
    }

    public Double getDeTime()
    {
        return deTime;
    }

    public Double getSampleRate()
    {
        return sampleRate;
    }

    public Double getRpm()
    {
        return rpm;
    }

    public Double getLoad()
    {
        return load;
    }

    public String getFaultType()
    {
        return faultType;
    }

    public Double getFaultSize()
    {
        return faultSize;
    }

    public String toCsvLine()
    {
        return String.join(",",
            nullToEmpty(header),
            nullToEmpty(version),
            nullToEmpty(globals),
            formatDouble(deTime),
            formatDouble(sampleRate),
            formatDouble(rpm),
            formatDouble(load),
            nullToEmpty(faultType),
            formatDouble(faultSize));
    }

    public static VibrationCsvProtocol parse(String line)
    {
        if (line == null)
        {
            throw new IllegalArgumentException("CSV protocol line cannot be null");
        }
        String[] parts = line.trim().split(",", -1);
        if (parts.length != FIELD_COUNT)
        {
            throw new IllegalArgumentException("CSV protocol field count must be exactly " + FIELD_COUNT + ", actual=" + parts.length + ": " + Arrays.toString(parts));
        }
        return new VibrationCsvProtocol(
            parts[0].trim(),
            parts[1].trim(),
            parts[2].trim(),
            parseDouble(parts[3].trim(), "DE_time"),
            parseDouble(parts[4].trim(), "sr"),
            parseDouble(parts[5].trim(), "rpm"),
            parseDouble(parts[6].trim(), "load"),
            parts[7].trim(),
            parseDouble(parts[8].trim(), "fault_size")
        );
    }

    private static Double parseDouble(String value, String field)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }
        try
        {
            return Double.valueOf(value);
        }
        catch (Exception ex)
        {
            throw new IllegalArgumentException("CSV protocol field " + field + " must be numeric, actual=" + value, ex);
        }
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    private static String formatDouble(Double value)
    {
        return value == null ? "" : String.format(java.util.Locale.US, "%.3f", value);
    }
}
