package com.ruoyi.sensor.tdengine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import com.taosdata.jdbc.AbstractConnection;
import com.taosdata.jdbc.enums.SchemalessProtocolType;
import com.taosdata.jdbc.enums.SchemalessTimestampType;

public class TDenginetest
{
    private static final String URL = "jdbc:TAOS-WS://127.0.0.1:6041/power?user=root&password=taosdata&varcharAsString=true";

    public static void main(String[] args) throws Exception
    {
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");

        try (Connection connection = DriverManager.getConnection(URL))
        {
            initSchema(connection);

            AbstractConnection conn = connection.unwrap(AbstractConnection.class);

            long baseTs = System.currentTimeMillis();
            System.out.println("Writing 1000 rows for each measurement...");
            for (int i = 0; i < 1000; i++)
            {
                long ts = baseTs + i;
                String rawLine = "sensor_raw_wave_st_ch1,deviceCode=test-device,channelId=1 sample_rate=1000,sample_index=" + i + ",waveform=" + String.format("%.3f", 0.123 + (i * 0.001)) + " " + ts;
                String fftLine = "sensor_fft_point_st_ch1,deviceCode=test-device,channelId=1 freq_bin=" + (i % 128) + ",amplitude=" + String.format("%.3f", 0.456 + (i * 0.001)) + " " + ts;
                String csvLine = "sensor_vibration_csv_st_ch1,deviceCode=test-device,channelId=1 de_time=" + String.format("%.3f", 1.0 + (i * 0.01)) + ",sr=1000,rpm=3000,load=0.5,fault_size=0.2,fault_type=\"normal\",protocol_header=\"head\",protocol_version=\"1.0\",protocol_globals=\"g\" " + ts;

                conn.write(rawLine, SchemalessProtocolType.LINE, SchemalessTimestampType.MILLI_SECONDS);
                conn.write(fftLine, SchemalessProtocolType.LINE, SchemalessTimestampType.MILLI_SECONDS);
                conn.write(csvLine, SchemalessProtocolType.LINE, SchemalessTimestampType.MILLI_SECONDS);
            }

            System.out.println("Write finished. Querying results...");
            printQuery(connection, "SHOW TABLES");
            printQuery(connection, "SELECT * FROM power.sensor_raw_wave_st_ch1 LIMIT 5");
            printQuery(connection, "SELECT * FROM power.sensor_fft_point_st_ch1 LIMIT 5");
            printQuery(connection, "SELECT * FROM power.sensor_vibration_csv_st_ch1 LIMIT 5");

            System.out.println("TDengine schemaless test inserted successfully.");
        }
    }

    private static void initSchema(Connection connection) throws Exception
    {
        try (Statement stmt = connection.createStatement())
        {
            stmt.execute("CREATE DATABASE IF NOT EXISTS power");
            stmt.execute("USE power");

            stmt.execute("CREATE STABLE IF NOT EXISTS sensor_raw_wave_st (ts TIMESTAMP, sample_rate INT, sample_index INT, waveform DOUBLE) TAGS (deviceCode NCHAR(64), channelId INT)");
            stmt.execute("CREATE STABLE IF NOT EXISTS sensor_fft_point_st (ts TIMESTAMP, freq_bin INT, amplitude DOUBLE) TAGS (deviceCode NCHAR(64), channelId INT)");
            stmt.execute("CREATE STABLE IF NOT EXISTS sensor_vibration_csv_st (ts TIMESTAMP, de_time DOUBLE, sr DOUBLE, rpm DOUBLE, load DOUBLE, fault_size DOUBLE, fault_type NCHAR(128), protocol_header NCHAR(128), protocol_version NCHAR(64), protocol_globals NCHAR(256)) TAGS (deviceCode NCHAR(64), channelId INT)");
        }
    }

    private static void printQuery(Connection connection, String sql) throws Exception
    {
        System.out.println("\n=== SQL: " + sql + " ===");
        try (Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery(sql))
        {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            while (rs.next())
            {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++)
                {
                    if (i > 1)
                    {
                        row.append(" | ");
                    }
                    row.append(metaData.getColumnLabel(i)).append("=").append(rs.getString(i));
                }
                System.out.println(row);
            }
        }
    }
}
