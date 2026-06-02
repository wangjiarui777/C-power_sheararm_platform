package com.ruoyi.sensor.tdengine;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TdengineQueryService
{
    @Value("${sensor.tdengine.enabled:false}")
    private boolean enabled;

    @Value("${sensor.tdengine.url:jdbc:TAOS-WS://127.0.0.1:6041/power?user=root&password=taosdata&varcharAsString=true}")
    private String url;

    @Value("${sensor.tdengine.database:power}")
    private String database;

    @Value("${sensor.tdengine.raw-super-table:sensor_raw_wave_st}")
    private String rawSuperTable;

    @Value("${sensor.tdengine.fft-super-table:sensor_fft_point_st}")
    private String fftSuperTable;

    public Map<String, Object> loadDiagnosisData(String deviceCode, Integer channelId, int timeLimit, int fftLimit)
    {
        assertEnabled();
        int safeChannelId = channelId == null || channelId <= 0 ? 1 : channelId;
        String rawTable = resolveTable(rawSuperTable, safeChannelId);
        String fftTable = resolveTable(fftSuperTable, safeChannelId);
        int safeTimeLimit = Math.max(1, timeLimit);
        int safeFftLimit = Math.max(1, fftLimit);

        List<String> deviceCandidates = buildDeviceCandidates(deviceCode, rawTable, safeChannelId);
        List<Double> waveform = new ArrayList<>();
        List<Map<String, Object>> fftRows = new ArrayList<>();
        String matchedDeviceCode = sanitizeDeviceCode(deviceCode);

        for (String candidate : deviceCandidates)
        {
            waveform = queryDoubleSeries(
                String.format(Locale.US,
                    "SELECT ts, waveform FROM %s WHERE deviceCode='%s' AND channelId=%d ORDER BY ts DESC LIMIT %d",
                    rawTable, candidate, safeChannelId, safeTimeLimit),
                "waveform");
            fftRows = queryRows(
                String.format(Locale.US,
                    "SELECT freq_bin, amplitude FROM %s WHERE deviceCode='%s' AND channelId=%d ORDER BY freq_bin ASC LIMIT %d",
                    fftTable, candidate, safeChannelId, safeFftLimit));
            if (!waveform.isEmpty() || !fftRows.isEmpty())
            {
                matchedDeviceCode = candidate;
                break;
            }
        }

        if (waveform.isEmpty() && !deviceCandidates.isEmpty())
        {
            String candidate = deviceCandidates.get(0);
            waveform = queryDoubleSeries(
                String.format(Locale.US,
                    "SELECT ts, waveform FROM %s WHERE deviceCode='%s' ORDER BY ts DESC LIMIT %d",
                    rawTable, candidate, safeTimeLimit),
                "waveform");
        }
        if (fftRows.isEmpty() && !deviceCandidates.isEmpty())
        {
            String candidate = deviceCandidates.get(0);
            fftRows = queryRows(
                String.format(Locale.US,
                    "SELECT freq_bin, amplitude FROM %s WHERE deviceCode='%s' ORDER BY freq_bin ASC LIMIT %d",
                    fftTable, candidate, safeFftLimit));
        }

        List<Integer> frequencyAxis = new ArrayList<>();
        List<Double> spectrum = new ArrayList<>();
        for (Map<String, Object> row : fftRows)
        {
            frequencyAxis.add(toInt(row.get("freq_bin")));
            spectrum.add(toDouble(row.get("amplitude")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", matchedDeviceCode);
        result.put("requestedDeviceCode", sanitizeDeviceCode(deviceCode));
        result.put("channelId", safeChannelId);
        result.put("waveform", waveform);
        result.put("frequencyAxis", frequencyAxis);
        result.put("spectrum", spectrum);
        result.put("confidence", calcConfidence(waveform, spectrum));
        result.put("diagnosis", spectrum.size() > 0 && max(spectrum) > 10 ? "状态异常" : "状态正常");
        result.put("diagnosisDetail", waveform.isEmpty() ? "未从 TDengine 读取到时域数据" : "已从 TDengine 读取时域和频域数据");
        return result;
    }

    private List<Double> queryDoubleSeries(String sql, String column)
    {
        List<Double> values = new ArrayList<>();
        List<Map<String, Object>> rows = queryRows(sql);
        for (int i = rows.size() - 1; i >= 0; i--)
        {
            values.add(toDouble(rows.get(i).get(column)));
        }
        return values;
    }

    private List<Map<String, Object>> queryRows(String sql)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery(sql))
        {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            while (rs.next())
            {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++)
                {
                    row.put(metaData.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("TDengine query failed: " + sql, ex);
        }
        return rows;
    }

    private void assertEnabled()
    {
        if (!enabled)
        {
            throw new IllegalStateException("TDengine is disabled, set sensor.tdengine.enabled=true");
        }
    }

    private String resolveTable(String tableName, Integer channelId)
    {
        String safeTable = tableName == null ? "" : tableName.trim();
        if (safeTable.isEmpty())
        {
            throw new IllegalStateException("TDengine table name is empty");
        }
        String qualified = safeTable.contains(".") ? safeTable : qualifyTable(safeTable);
        String channelSuffix = "_ch" + (channelId == null || channelId <= 0 ? 1 : channelId);
        String channelQualified = qualified.endsWith(channelSuffix) ? qualified : qualified + channelSuffix;
        return tableExists(channelQualified) ? channelQualified : qualified;
    }

    private String qualifyTable(String tableName)
    {
        String safeTable = tableName == null ? "" : tableName.trim();
        if (safeTable.isEmpty())
        {
            throw new IllegalStateException("TDengine table name is empty");
        }
        if (safeTable.contains("."))
        {
            return safeTable;
        }
        String safeDatabase = database == null ? "" : database.trim();
        if (safeDatabase.isEmpty())
        {
            return safeTable;
        }
        return safeDatabase + "." + safeTable;
    }

    private List<String> buildDeviceCandidates(String deviceCode, String tableName, int channelId)
    {
        List<String> candidates = new ArrayList<>();
        String normalized = sanitizeDeviceCode(deviceCode);
        if (!normalized.isEmpty())
        {
            candidates.add(normalized);
        }

        List<Map<String, Object>> tags = queryRows(String.format(Locale.US,
            "SHOW TAGS FROM %s", tableName));
        for (Map<String, Object> row : tags)
        {
            for (Object value : row.values())
            {
                String candidate = sanitizeDeviceCode(String.valueOf(value));
                if (!candidate.isEmpty() && !candidates.contains(candidate))
                {
                    candidates.add(candidate);
                }
            }
        }

        List<Map<String, Object>> tables = queryRows(String.format(Locale.US,
            "SHOW TABLES LIKE '%s_ch%d'", stripDatabasePrefix(tableName), channelId));
        for (Map<String, Object> row : tables)
        {
            for (Object value : row.values())
            {
                String candidate = extractDeviceCodeFromTableName(String.valueOf(value), channelId);
                if (!candidate.isEmpty() && !candidates.contains(candidate))
                {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    private boolean tableExists(String tableName)
    {
        try
        {
            return !queryRows("SHOW TABLES LIKE '" + stripDatabasePrefix(tableName) + "'").isEmpty()
                || !queryRows("SHOW STABLES LIKE '" + stripDatabasePrefix(tableName) + "'").isEmpty();
        }
        catch (Exception ex)
        {
            return true;
        }
    }

    private String stripDatabasePrefix(String tableName)
    {
        String safe = tableName == null ? "" : tableName.trim();
        int idx = safe.indexOf('.');
        return idx >= 0 ? safe.substring(idx + 1) : safe;
    }

    private String extractDeviceCodeFromTableName(String tableName, int channelId)
    {
        String safe = stripDatabasePrefix(tableName);
        String suffix = "_ch" + channelId;
        if (safe.endsWith(suffix))
        {
            return safe.substring(0, safe.length() - suffix.length());
        }
        return safe;
    }

    private String sanitizeDeviceCode(String deviceCode)
    {
        if (deviceCode == null || deviceCode.trim().isEmpty())
        {
            return "BEARING-001";
        }
        return deviceCode.trim().replace("'", "");
    }

    private Double toDouble(Object value)
    {
        if (value == null)
        {
            return 0D;
        }
        if (value instanceof Number)
        {
            return ((Number) value).doubleValue();
        }
        if (value instanceof BigDecimal)
        {
            return ((BigDecimal) value).doubleValue();
        }
        try
        {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (Exception ex)
        {
            return 0D;
        }
    }

    private Integer toInt(Object value)
    {
        if (value == null)
        {
            return 0;
        }
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        try
        {
            return Integer.parseInt(String.valueOf(value));
        }
        catch (Exception ex)
        {
            return 0;
        }
    }

    private double calcConfidence(List<Double> waveform, List<Double> spectrum)
    {
        double waveformScore = waveform.isEmpty() ? 0D : Math.min(40D, avg(waveform) * 4D);
        double spectrumScore = spectrum.isEmpty() ? 0D : Math.min(60D, max(spectrum) * 3D);
        return Math.max(0D, Math.min(99D, waveformScore + spectrumScore));
    }

    private double avg(List<Double> values)
    {
        if (values == null || values.isEmpty())
        {
            return 0D;
        }
        double sum = 0D;
        for (Double value : values)
        {
            sum += value == null ? 0D : value;
        }
        return sum / values.size();
    }

    private double max(List<Double> values)
    {
        double max = 0D;
        for (Double value : values)
        {
            max = Math.max(max, value == null ? 0D : value);
        }
        return max;
    }
}
