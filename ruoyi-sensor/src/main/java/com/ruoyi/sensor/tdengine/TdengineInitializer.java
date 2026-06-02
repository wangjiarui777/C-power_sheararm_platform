package com.ruoyi.sensor.tdengine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TDengine 初始化器。
 *
 * 职责：
 * 1. 连接 TDengine WebSocket JDBC
 * 2. 创建数据库
 * 3. 创建超级表
 */
@Component
public class TdengineInitializer
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

    public void init()
    {
        if (!enabled)
        {
            return;
        }

        try
        {
            Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
            try (Connection conn = DriverManager.getConnection(url))
            {
                try (Statement stmt = conn.createStatement())
                {
                    stmt.execute("CREATE DATABASE IF NOT EXISTS power");
                    stmt.execute("USE power");
                    stmt.execute("CREATE STABLE IF NOT EXISTS " + rawSuperTable + " (ts TIMESTAMP, sample_rate INT, sample_index INT, waveform DOUBLE) TAGS (deviceCode NCHAR(64), channelId INT)");
                    stmt.execute("CREATE STABLE IF NOT EXISTS " + fftSuperTable + " (ts TIMESTAMP, freq_bin INT, amplitude DOUBLE) TAGS (deviceCode NCHAR(64), channelId INT)");
                    stmt.execute("CREATE STABLE IF NOT EXISTS " + csvSuperTable + " (ts TIMESTAMP, de_time DOUBLE, sr DOUBLE, rpm DOUBLE, load DOUBLE, fault_size DOUBLE, fault_type NCHAR(128), protocol_header NCHAR(128), protocol_version NCHAR(64), protocol_globals NCHAR(256)) TAGS (deviceCode NCHAR(64), channelId INT)");
                }
            }
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("TDengine 初始化失败", ex);
        }
    }
}
