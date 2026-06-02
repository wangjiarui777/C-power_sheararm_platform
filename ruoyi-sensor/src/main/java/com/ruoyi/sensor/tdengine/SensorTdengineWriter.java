package com.ruoyi.sensor.tdengine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.sensor.domain.dto.SensorSampleDto;
import com.ruoyi.sensor.domain.dto.VibrationCsvRecord;

@Component
public class SensorTdengineWriter
{
    @Value("${sensor.tdengine.enabled:false}")
    private boolean enabled;

    @Value("${sensor.tdengine.url:jdbc:TAOS-WS://127.0.0.1:6041/power?user=root&password=taosdata&varcharAsString=true}")
    private String url;

    @Value("${sensor.tdengine.raw-super-table:sensor_raw_wave_st}")
    private String rawSuperTable;

    @Value("${sensor.tdengine.fft-super-table:sensor_fft_point_st}")
    private String fftSuperTable;

    @Value("${sensor.tdengine.csv-super-table:sensor_vibration_csv_st}")
    private String csvSuperTable;

    public void writeRawWave(String deviceCode, Integer channelId, SensorSampleDto sample)
    {
        assertEnabled();
        if (sample == null || sample.getWaveform() == null || sample.getWaveform().length == 0)
        {
            return;
        }

        String measurement = buildChildTableName(rawSuperTable, channelId);
        long baseTs = sample.getSampleTime() > 0 ? sample.getSampleTime() : System.currentTimeMillis();
        StringBuilder lines = new StringBuilder();
        double[] waveform = sample.getWaveform();
        for (int i = 0; i < waveform.length; i++)
        {
            lines.append(measurement)
                .append(",deviceCode=").append(escapeTagValue(deviceCode))
                .append(",channelId=").append(safeChannelId(channelId))
                .append(' ')
                .append("sample_rate=").append(formatNumber(sample.getSampleRate()))
                .append(",sample_index=").append(i)
                .append(",waveform=").append(formatNumber(waveform[i]))
                .append(' ')
                .append(baseTs + i)
                .append('\n');
        }

        writeSchemaless(lines.toString());
    }

    public void writeFftPoints(String deviceCode, Integer channelId, List<Double> amplitudes)
    {
        assertEnabled();
        if (amplitudes == null || amplitudes.isEmpty())
        {
            return;
        }

        String measurement = buildChildTableName(fftSuperTable, channelId);
        long ts = System.currentTimeMillis();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < amplitudes.size(); i++)
        {
            Double amp = amplitudes.get(i);
            lines.append(measurement)
                .append(",deviceCode=").append(escapeTagValue(deviceCode))
                .append(",channelId=").append(safeChannelId(channelId))
                .append(' ')
                .append("freq_bin=").append(i + 1)
                .append(",amplitude=").append(formatNumber(amp == null ? 0D : amp))
                .append(' ')
                .append(ts)
                .append('\n');
        }

        writeSchemaless(lines.toString());
    }

    public void writeCsvRecord(String deviceCode, Integer channelId, VibrationCsvRecord record, java.util.Date sampleTime)
    {
        assertEnabled();
        if (record == null)
        {
            return;
        }

        String measurement = buildChildTableName(csvSuperTable, channelId);
        long ts = sampleTime == null ? System.currentTimeMillis() : sampleTime.getTime();
        String line = measurement
            + ",deviceCode=" + escapeTagValue(deviceCode)
            + ",channelId=" + safeChannelId(channelId)
            + ' '
            + "de_time=" + formatNumber(record.getDeTime())
            + ",sr=" + formatNumber(record.getSampleRate())
            + ",rpm=" + formatNumber(record.getRpm())
            + ",load=" + formatNumber(record.getLoad())
            + ",fault_size=" + formatNumber(record.getFaultSize())
            + ",fault_type=\"" + escapeFieldValue(record.getFaultType()) + "\""
            + ",protocol_header=\"" + escapeFieldValue(record.getHeader()) + "\""
            + ",protocol_version=\"" + escapeFieldValue(record.getVersion()) + "\""
            + ",protocol_globals=\"" + escapeFieldValue(record.getGlobals()) + "\""
            + ' '
            + ts;

        writeSchemaless(line);
    }

    private void writeSchemaless(String payload)
    {
        try (Connection connection = getConnection())
        {
            Class<?> abstractConnectionClass = Class.forName("com.taosdata.jdbc.ws.AbstractConnection");
            Object conn = connection.unwrap(abstractConnectionClass);
            Class<?> protocolTypeClass = Class.forName("com.taosdata.jdbc.ws.SchemalessProtocolType");
            Class<?> timestampTypeClass = Class.forName("com.taosdata.jdbc.ws.SchemalessTimestampType");

            @SuppressWarnings("unchecked")
            Object lineType = Enum.valueOf((Class<Enum>) protocolTypeClass.asSubclass(Enum.class), "LINE");
            @SuppressWarnings("unchecked")
            Object msType = Enum.valueOf((Class<Enum>) timestampTypeClass.asSubclass(Enum.class), "MILLI_SECONDS");

            abstractConnectionClass.getMethod("write", String.class, protocolTypeClass, timestampTypeClass)
                .invoke(conn, payload, lineType, msType);
        }
        catch (Exception ex)
        {
            throw new RuntimeException("TDengine schemaless write failed", ex);
        }
    }

    private Connection getConnection() throws SQLException
    {
        if (url == null || url.isEmpty())
        {
            throw new IllegalStateException("TDengine JDBC URL is empty");
        }
        if (!url.startsWith("jdbc:TAOS-WS://") && !url.startsWith("jdbc:taos-ws://"))
        {
            throw new IllegalStateException("TDengine URL must use jdbc:TAOS-WS://");
        }
        return DriverManager.getConnection(url);
    }

    private void assertEnabled()
    {
        if (!enabled)
        {
            throw new IllegalStateException("TDengine is disabled, set sensor.tdengine.enabled=true");
        }
    }

    private String buildChildTableName(String prefix, Integer channelId)
    {
        return prefix + "_ch" + safeChannelId(channelId);
    }

    private int safeChannelId(Integer channelId)
    {
        return channelId == null || channelId <= 0 ? 1 : channelId;
    }

    private String escapeTagValue(String value)
    {
        if (value == null || value.isEmpty())
        {
            return "unknown";
        }
        return value.replace(" ", "_").replace(",", "_").replace("=", "_");
    }

    private String escapeFieldValue(String value)
    {
        if (value == null)
        {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String formatNumber(Number value)
    {
        return value == null ? "0" : String.format(Locale.US, "%s", value);
    }
}
