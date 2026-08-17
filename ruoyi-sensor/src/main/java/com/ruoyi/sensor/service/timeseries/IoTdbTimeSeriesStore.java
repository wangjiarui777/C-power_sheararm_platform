package com.ruoyi.sensor.service.timeseries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.ruoyi.sensor.domain.dto.TelemetryEnvelope;
import com.ruoyi.sensor.domain.dto.VibrationFrameEnvelope;
import org.apache.iotdb.isession.ITableSession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;
import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.apache.tsfile.utils.Binary;
import org.apache.tsfile.write.record.Tablet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "sensor", name = "store-type", havingValue = "iotdb", matchIfMissing = true)
public class IoTdbTimeSeriesStore implements TimeSeriesStore
{
    private static final Logger log = LoggerFactory.getLogger(IoTdbTimeSeriesStore.class);
    private static final String TELEMETRY_TABLE = "telemetry_metric";
    private static final String FRAME_TABLE = "vibration_frame";
    private static final String DIAGNOSIS_TABLE = "diagnosis_result";

    @Value("${sensor.iotdb.enabled:true}")
    private boolean enabled;

    @Value("${sensor.iotdb.database:monitoring}")
    private String database;

    @Value("${sensor.iotdb.node-urls:localhost:6667}")
    private String nodeUrls;

    @Value("${sensor.iotdb.username:root}")
    private String username;

    @Value("${sensor.iotdb.password:root}")
    private String password;

    @Value("${sensor.iotdb.query-timeout-ms:15000}")
    private long queryTimeoutMs;

    @Value("${sensor.iotdb.connection-timeout-ms:3000}")
    private int connectionTimeoutMs;

    @Value("${sensor.iotdb.wait-session-timeout-ms:3000}")
    private long waitSessionTimeoutMs;

    @Value("${sensor.iotdb.max-retry-count:1}")
    private int maxRetryCount;

    @Value("${sensor.iotdb.retry-interval-ms:500}")
    private long retryIntervalMs;

    @Value("${sensor.iotdb.reconnect-interval-seconds:30}")
    private long reconnectIntervalSeconds;

    @Value("${sensor.iotdb.rpc-compression:true}")
    private boolean rpcCompression;

    @Value("${sensor.iotdb.redirection:true}")
    private boolean redirection;

    @Value("${sensor.iotdb.auto-fetch-nodes:true}")
    private boolean autoFetchNodes;

    @Value("${sensor.iotdb.use-ssl:false}")
    private boolean useSsl;

    @Value("${sensor.iotdb.trust-store:}")
    private String trustStore;

    @Value("${sensor.iotdb.trust-store-password:}")
    private String trustStorePassword;

    @Value("${sensor.iotdb.fetch-size:512}")
    private int fetchSize;

    @Value("${sensor.iotdb.session-pool-size:4}")
    private int sessionPoolSize;

    @Value("${sensor.iotdb.telemetry-ttl-days:1095}")
    private int telemetryTtlDays;

    @Value("${sensor.iotdb.frame-ttl-days:90}")
    private int frameTtlDays;

    @Value("${sensor.iotdb.diagnosis-ttl-days:3650}")
    private int diagnosisTtlDays;

    @Value("${sensor.iotdb.timestamp-precision:us}")
    private String timestampPrecision;

    private volatile ITableSessionPool sessionPool;
    private volatile boolean available;
    private volatile Date lastSuccessfulWriteTime;
    private volatile Date lastSuccessfulOperationTime;
    private volatile Date lastConnectionAttemptTime;
    private volatile Date lastFailureTime;
    private volatile String lastError;
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicBoolean connectionAttemptInProgress = new AtomicBoolean(false);
    private final ScheduledExecutorService connectionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "iotdb-timeseries-connector");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public TimeSeriesStoreStatus getStatus()
    {
        return new TimeSeriesStoreStatus("iotdb", currentState(), enabled, available,
                lastSuccessfulWriteTime, lastSuccessfulOperationTime,
                lastConnectionAttemptTime, lastFailureTime, failureCount.get(), lastError);
    }

    @PostConstruct
    public void init()
    {
        if (!enabled)
        {
            log.info("IoTDB timeseries store disabled by configuration.");
            return;
        }
        requestConnection();
        long interval = Math.max(5L, reconnectIntervalSeconds);
        connectionExecutor.scheduleWithFixedDelay(this::retryConnection, interval, interval, TimeUnit.SECONDS);
    }

    private void retryConnection()
    {
        if (enabled && !available)
        {
            requestConnection();
        }
    }

    private void requestConnection()
    {
        if (!enabled || available || connectionExecutor.isShutdown()
                || !connectionAttemptInProgress.compareAndSet(false, true))
        {
            return;
        }
        connectionExecutor.execute(() -> {
            try
            {
                initializeConnection();
            }
            finally
            {
                connectionAttemptInProgress.set(false);
            }
        });
    }

    private void initializeConnection()
    {
        if (available || connectionExecutor.isShutdown())
        {
            return;
        }
        lastConnectionAttemptTime = new Date();
        ITableSessionPool bootstrapPool = null;
        ITableSessionPool candidate = null;
        try
        {
            validateConfiguration();
            bootstrapPool = buildPool(null);
            ensureDatabase(bootstrapPool);
            bootstrapPool.close();
            bootstrapPool = null;

            candidate = buildPool(database);
            initializeSchema(candidate);
            replacePool(candidate);
            candidate = null;
            markOperationSuccess();
            log.info("IoTDB timeseries store connected.");
        }
        catch (Exception ex)
        {
            if (bootstrapPool != null)
            {
                bootstrapPool.close();
            }
            if (candidate != null)
            {
                candidate.close();
            }
            recordFailure(ex);
            log.warn("IoTDB initialization failed, timeseries features will degrade gracefully.", ex);
        }
    }

    @PreDestroy
    public void destroy()
    {
        connectionExecutor.shutdownNow();
        ITableSessionPool pool = detachPool();
        if (pool != null)
        {
            pool.close();
        }
    }

    @Override
    public boolean writeTelemetry(TelemetryEnvelope envelope)
    {
        ITableSessionPool pool = availablePool();
        if (pool == null || envelope == null || envelope.getValue() == null)
        {
            requestConnection();
            return false;
        }
        envelope.normalize();
        try (ITableSession session = pool.getSession())
        {
            Tablet tablet = new Tablet(
                    TELEMETRY_TABLE,
                    Arrays.asList("org_code", "line_code", "device_code", "point_code", "metric_code",
                            "signal_type", "unit", "channel_id", "value", "quality", "receive_time", "sequence"),
                    Arrays.asList(TSDataType.STRING, TSDataType.STRING, TSDataType.STRING, TSDataType.STRING,
                            TSDataType.STRING, TSDataType.STRING, TSDataType.STRING, TSDataType.INT32,
                            TSDataType.DOUBLE, TSDataType.STRING, TSDataType.TIMESTAMP, TSDataType.INT64),
                    Arrays.asList(ColumnCategory.TAG, ColumnCategory.TAG, ColumnCategory.TAG, ColumnCategory.TAG,
                            ColumnCategory.TAG, ColumnCategory.ATTRIBUTE, ColumnCategory.ATTRIBUTE,
                            ColumnCategory.FIELD, ColumnCategory.FIELD, ColumnCategory.FIELD,
                            ColumnCategory.FIELD, ColumnCategory.FIELD),
                    1);
            tablet.addTimestamp(0, toStoreTimestamp(envelope.getSampleTime()));
            tablet.addValue("org_code", 0, "");
            tablet.addValue("line_code", 0, "");
            tablet.addValue("device_code", 0, safeText(envelope.getDeviceCode()));
            tablet.addValue("point_code", 0, safeText(resolvePointCode(envelope)));
            tablet.addValue("metric_code", 0, safeText(envelope.getMetricCode()));
            tablet.addValue("signal_type", 0, safeText(envelope.getSignalType()));
            tablet.addValue("unit", 0, safeText(envelope.getUnit()));
            tablet.addValue("channel_id", 0, envelope.getChannelId() == null ? 0 : envelope.getChannelId());
            tablet.addValue("value", 0, envelope.getValue());
            tablet.addValue("quality", 0, safeText(envelope.getQuality()));
            tablet.addValue("receive_time", 0, toStoreTimestamp(envelope.getReceiveTime()));
            tablet.addValue("sequence", 0, envelope.getSequence() == null ? 0L : envelope.getSequence());
            session.insert(tablet);
            markWriteSuccess();
            return true;
        }
        catch (Exception ex)
        {
            invalidatePool(pool, ex);
            log.warn("Failed to write telemetry to IoTDB, device={}, metric={}",
                    envelope.getDeviceCode(), envelope.getMetricCode(), ex);
            return false;
        }
    }

    @Override
    public boolean writeVibrationFrame(VibrationFrameEnvelope envelope)
    {
        ITableSessionPool pool = availablePool();
        if (pool == null || envelope == null)
        {
            requestConnection();
            return false;
        }
        envelope.normalize();
        try (ITableSession session = pool.getSession())
        {
            byte[] waveform = TimeSeriesFrameCodec.encode(envelope.getWaveform());
            byte[] spectrum = TimeSeriesFrameCodec.encode(envelope.getSpectrum());
            int fftSize = envelope.getSpectrum() == null ? 0 : envelope.getSpectrum().size();

            Tablet tablet = new Tablet(
                    FRAME_TABLE,
                    Arrays.asList("device_code", "point_code", "axis", "unit", "channel_id", "sample_rate",
                            "sample_count", "waveform", "spectrum", "freq_step", "fft_size", "rpm", "load",
                            "fault_type", "fault_size", "quality", "receive_time", "sequence"),
                    Arrays.asList(TSDataType.STRING, TSDataType.STRING, TSDataType.STRING, TSDataType.STRING,
                            TSDataType.INT32, TSDataType.INT32, TSDataType.INT32, TSDataType.BLOB, TSDataType.BLOB,
                            TSDataType.DOUBLE, TSDataType.INT32, TSDataType.DOUBLE, TSDataType.DOUBLE,
                            TSDataType.STRING, TSDataType.DOUBLE, TSDataType.STRING, TSDataType.TIMESTAMP,
                            TSDataType.INT64),
                    Arrays.asList(ColumnCategory.TAG, ColumnCategory.TAG, ColumnCategory.ATTRIBUTE,
                            ColumnCategory.ATTRIBUTE, ColumnCategory.FIELD, ColumnCategory.FIELD,
                            ColumnCategory.FIELD, ColumnCategory.FIELD, ColumnCategory.FIELD,
                            ColumnCategory.FIELD, ColumnCategory.FIELD, ColumnCategory.FIELD,
                            ColumnCategory.FIELD, ColumnCategory.FIELD, ColumnCategory.FIELD,
                            ColumnCategory.FIELD, ColumnCategory.FIELD, ColumnCategory.FIELD),
                    1);
            tablet.addTimestamp(0, toStoreTimestamp(envelope.getSampleTime()));
            tablet.addValue("device_code", 0, safeText(envelope.getDeviceCode()));
            tablet.addValue("point_code", 0, safeText(resolvePointCode(envelope)));
            tablet.addValue("axis", 0, safeText(envelope.getAxis()));
            tablet.addValue("unit", 0, safeText(envelope.getUnit()));
            tablet.addValue("channel_id", 0, envelope.getChannelId() == null ? 0 : envelope.getChannelId());
            tablet.addValue("sample_rate", 0, envelope.getSampleRate() == null ? 0 : envelope.getSampleRate());
            tablet.addValue("sample_count", 0, envelope.getSampleCount() == null ? 0 : envelope.getSampleCount());
            tablet.addValue("waveform", 0, new Binary(waveform));
            tablet.addValue("spectrum", 0, new Binary(spectrum));
            tablet.addValue("freq_step", 0, envelope.getFreqStep() == null ? 0D : envelope.getFreqStep());
            tablet.addValue("fft_size", 0, fftSize);
            tablet.addValue("rpm", 0, envelope.getRpm() == null ? 0D : envelope.getRpm());
            tablet.addValue("load", 0, envelope.getLoad() == null ? 0D : envelope.getLoad());
            tablet.addValue("fault_type", 0, safeText(envelope.getFaultType()));
            tablet.addValue("fault_size", 0, envelope.getFaultSize() == null ? 0D : envelope.getFaultSize());
            tablet.addValue("quality", 0, safeText(envelope.getQuality()));
            tablet.addValue("receive_time", 0, toStoreTimestamp(envelope.getReceiveTime()));
            tablet.addValue("sequence", 0, envelope.getSequence() == null ? 0L : envelope.getSequence());
            session.insert(tablet);
            markWriteSuccess();
            return true;
        }
        catch (Exception ex)
        {
            invalidatePool(pool, ex);
            log.warn("Failed to write vibration frame to IoTDB, device={}, channel={}",
                    envelope.getDeviceCode(), envelope.getChannelId(), ex);
            return false;
        }
    }

    @Override
    public boolean writeDiagnosisResult(DiagnosisResultSnapshot result)
    {
        ITableSessionPool pool = availablePool();
        if (pool == null || result == null || result.getRecordId() == null || result.getCreateTime() == null)
        {
            requestConnection();
            return false;
        }
        List<String> columns = Arrays.asList("record_id", "device_code", "point_key", "analysis_mode",
            "model_version", "batch_id", "task_id", "point_id", "channel_id", "model_release_id",
            "source_file", "sample_rate", "diagnosis_result", "closed_prediction", "confidence",
            "health_index", "risk_level", "alarm_level", "diagnosis_detail", "decision_reason",
            "unknown_ratio", "segment_consistency", "mean_mahalanobis", "mean_entropy", "rms", "peak",
            "top_probabilities", "evidence", "timeseries_ref", "sample_time", "update_time", "remark");
        List<TSDataType> types = Arrays.asList(TSDataType.STRING, TSDataType.STRING, TSDataType.STRING,
            TSDataType.STRING, TSDataType.STRING, TSDataType.INT64, TSDataType.INT64, TSDataType.INT64,
            TSDataType.INT32, TSDataType.INT64, TSDataType.STRING, TSDataType.DOUBLE, TSDataType.STRING,
            TSDataType.STRING, TSDataType.DOUBLE, TSDataType.INT32, TSDataType.STRING, TSDataType.STRING,
            TSDataType.STRING, TSDataType.STRING, TSDataType.DOUBLE, TSDataType.DOUBLE, TSDataType.DOUBLE,
            TSDataType.DOUBLE, TSDataType.DOUBLE, TSDataType.DOUBLE, TSDataType.STRING, TSDataType.STRING,
            TSDataType.STRING, TSDataType.TIMESTAMP, TSDataType.TIMESTAMP, TSDataType.STRING);
        List<ColumnCategory> categories = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++)
        {
            categories.add(i < 5 ? ColumnCategory.TAG : ColumnCategory.FIELD);
        }
        try (ITableSession session = pool.getSession())
        {
            Tablet tablet = new Tablet(DIAGNOSIS_TABLE, columns, types, categories, 1);
            tablet.addTimestamp(0, toStoreTimestamp(result.getCreateTime()));
            tablet.addValue("record_id", 0, String.valueOf(result.getRecordId()));
            tablet.addValue("device_code", 0, safeText(result.getDeviceCode()));
            tablet.addValue("point_key", 0, diagnosisPointKey(result));
            tablet.addValue("analysis_mode", 0, safeText(result.getAnalysisMode()));
            tablet.addValue("model_version", 0, safeText(result.getModelVersion()));
            addValue(tablet, "batch_id", result.getBatchId());
            addValue(tablet, "task_id", result.getTaskId());
            addValue(tablet, "point_id", result.getPointId());
            addValue(tablet, "channel_id", result.getChannelId());
            addValue(tablet, "model_release_id", result.getModelReleaseId());
            addValue(tablet, "source_file", result.getSourceFile());
            addValue(tablet, "sample_rate", result.getSampleRate());
            addValue(tablet, "diagnosis_result", result.getDiagnosisResult());
            addValue(tablet, "closed_prediction", result.getClosedPrediction());
            addValue(tablet, "confidence", decimalValue(result.getConfidence()));
            addValue(tablet, "health_index", result.getHealthIndex());
            addValue(tablet, "risk_level", result.getRiskLevel());
            addValue(tablet, "alarm_level", result.getAlarmLevel());
            addValue(tablet, "diagnosis_detail", result.getDiagnosisDetail());
            addValue(tablet, "decision_reason", result.getDecisionReason());
            addValue(tablet, "unknown_ratio", decimalValue(result.getUnknownRatio()));
            addValue(tablet, "segment_consistency", decimalValue(result.getSegmentConsistency()));
            addValue(tablet, "mean_mahalanobis", decimalValue(result.getMeanMahalanobis()));
            addValue(tablet, "mean_entropy", decimalValue(result.getMeanEntropy()));
            addValue(tablet, "rms", result.getRms());
            addValue(tablet, "peak", result.getPeak());
            addValue(tablet, "top_probabilities", result.getTopProbabilities());
            addValue(tablet, "evidence", result.getEvidence());
            addValue(tablet, "timeseries_ref", result.getTimeseriesRef());
            addValue(tablet, "sample_time", storeTimestamp(result.getSampleTime()));
            addValue(tablet, "update_time", storeTimestamp(result.getUpdateTime()));
            addValue(tablet, "remark", result.getRemark());
            session.insert(tablet);
            markWriteSuccess();
            return true;
        }
        catch (Exception ex)
        {
            invalidatePool(pool, ex);
            log.warn("Failed to write diagnosis to IoTDB, record={}, device={}",
                result.getRecordId(), result.getDeviceCode(), ex);
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> queryTelemetryTrend(String deviceCode, String pointCode, String metricCode,
                                                         Date from, Date to, int limit)
    {
        ITableSessionPool pool = requireAvailablePool();
        String sql = String.format(Locale.ROOT,
                "SELECT value, quality, receive_time, sequence FROM %s WHERE device_code='%s' AND point_code='%s' AND metric_code='%s'%s%s ORDER BY time DESC LIMIT %d",
                TELEMETRY_TABLE, literal(deviceCode), literal(pointCode), literal(metricCode),
                from == null ? "" : " AND time >= " + toStoreTimestamp(from),
                to == null ? "" : " AND time <= " + toStoreTimestamp(to),
                Math.max(1, limit));
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ITableSession session = pool.getSession();
             SessionDataSet dataSet = session.executeQueryStatement(sql, queryTimeoutMs))
        {
            while (dataSet.hasNext())
            {
                RowRecord record = dataSet.next();
                if (record == null)
                {
                    continue;
                }
                List<Field> fields = record.getFields();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", fromStoreTimestamp(record.getTimestamp()));
                row.put("value", fields.size() > 0 ? doubleField(fields.get(0)) : null);
                row.put("quality", fields.size() > 1 ? stringField(fields.get(1)) : null);
                row.put("receiveTime", fields.size() > 2 ? fromStoreTimestamp(longField(fields.get(2))) : null);
                row.put("sequence", fields.size() > 3 ? longField(fields.get(3)) : null);
                rows.add(row);
            }
        }
        catch (Exception ex)
        {
            invalidatePool(pool, ex);
            log.warn("Failed to query telemetry trend from IoTDB, device={}, point={}, metric={}",
                    deviceCode, pointCode, metricCode, ex);
            throw new TimeSeriesStoreUnavailableException("IoTDB telemetry query failed", ex);
        }
        markOperationSuccess();
        Collections.reverse(rows);
        return rows;
    }

    @Override
    public VibrationFrameSnapshot loadLatestVibrationFrame(String deviceCode, Integer channelId)
    {
        List<VibrationFrameSnapshot> frames = loadFrames(deviceCode, channelId, 1);
        return frames.isEmpty() ? null : frames.get(0);
    }

    @Override
    public List<VibrationFrameSnapshot> loadRecentVibrationFrames(String deviceCode, Integer channelId, int limit)
    {
        return loadFrames(deviceCode, channelId, Math.max(1, limit));
    }

    @Override
    public DiagnosisResultSnapshot loadLatestDiagnosis(String deviceCode)
    {
        List<DiagnosisResultSnapshot> rows = queryDiagnosisHistory(deviceCode, null, null, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<DiagnosisResultSnapshot> queryDiagnosisHistory(String deviceCode, Date from, Date to, int limit)
    {
        return queryDiagnosisHistory(deviceCode, null, from, to, limit);
    }

    @Override
    public List<DiagnosisResultSnapshot> queryDiagnosisHistory(String deviceCode, Long pointId, Date from, Date to,
                                                                int limit)
    {
        ITableSessionPool pool = requireAvailablePool();
        String sql = "SELECT record_id,batch_id,task_id,point_id,channel_id,model_release_id,source_file,"
            + "sample_rate,diagnosis_result,closed_prediction,confidence,health_index,risk_level,alarm_level,"
            + "diagnosis_detail,decision_reason,unknown_ratio,segment_consistency,mean_mahalanobis,mean_entropy,"
            + "rms,peak,top_probabilities,evidence,timeseries_ref,sample_time,update_time,remark,"
            + "device_code,analysis_mode,model_version FROM " + DIAGNOSIS_TABLE
            + " WHERE 1=1"
            + (deviceCode == null || deviceCode.isBlank() ? "" : " AND device_code='" + literal(deviceCode) + "'")
            + (pointId == null ? "" : " AND point_id=" + pointId)
            + (from == null ? "" : " AND time >= " + toStoreTimestamp(from))
            + (to == null ? "" : " AND time <= " + toStoreTimestamp(to))
            + " ORDER BY time DESC LIMIT " + Math.max(1, Math.min(5000, limit));
        List<DiagnosisResultSnapshot> rows = new ArrayList<>();
        try (ITableSession session = pool.getSession();
             SessionDataSet dataSet = session.executeQueryStatement(sql, queryTimeoutMs))
        {
            while (dataSet.hasNext())
            {
                RowRecord row = dataSet.next();
                if (row != null)
                {
                    rows.add(mapDiagnosis(row));
                }
            }
        }
        catch (Exception ex)
        {
            invalidatePool(pool, ex);
            log.warn("Failed to query diagnoses from IoTDB, device={}", deviceCode, ex);
            throw new TimeSeriesStoreUnavailableException("IoTDB diagnosis query failed", ex);
        }
        markOperationSuccess();
        return rows;
    }

    private List<VibrationFrameSnapshot> loadFrames(String deviceCode, Integer channelId, int limit)
    {
        ITableSessionPool pool = requireAvailablePool();
        String sql = String.format(Locale.ROOT,
                "SELECT point_code, channel_id, sample_rate, sample_count, waveform, spectrum, freq_step, fft_size, rpm, load, fault_type, fault_size, quality, receive_time, sequence FROM %s WHERE device_code='%s'%s ORDER BY time DESC LIMIT %d",
                FRAME_TABLE, literal(deviceCode),
                channelId == null ? "" : " AND channel_id = " + channelId,
                Math.max(1, limit));
        List<VibrationFrameSnapshot> frames = new ArrayList<>();
        try (ITableSession session = pool.getSession();
             SessionDataSet dataSet = session.executeQueryStatement(sql, queryTimeoutMs))
        {
            while (dataSet.hasNext())
            {
                RowRecord record = dataSet.next();
                if (record == null)
                {
                    continue;
                }
                List<Field> fields = record.getFields();
                VibrationFrameSnapshot frame = new VibrationFrameSnapshot();
                frame.setDeviceCode(deviceCode);
                frame.setPointCode(fields.size() > 0 ? stringField(fields.get(0)) : null);
                frame.setChannelId(fields.size() > 1 ? intField(fields.get(1)) : null);
                frame.setSampleRate(fields.size() > 2 ? intField(fields.get(2)) : null);
                frame.setSampleCount(fields.size() > 3 ? intField(fields.get(3)) : null);
                frame.setWaveform(fields.size() > 4 ? blobField(fields.get(4)) : new ArrayList<>());
                frame.setSpectrum(fields.size() > 5 ? blobField(fields.get(5)) : new ArrayList<>());
                frame.setFreqStep(fields.size() > 6 ? doubleField(fields.get(6)) : null);
                frame.setFftSize(fields.size() > 7 ? intField(fields.get(7)) : null);
                frame.setRpm(fields.size() > 8 ? doubleField(fields.get(8)) : null);
                frame.setLoad(fields.size() > 9 ? doubleField(fields.get(9)) : null);
                frame.setFaultType(fields.size() > 10 ? stringField(fields.get(10)) : null);
                frame.setFaultSize(fields.size() > 11 ? doubleField(fields.get(11)) : null);
                frame.setQuality(fields.size() > 12 ? stringField(fields.get(12)) : null);
                frame.setReceiveTime(fields.size() > 13 ? fromStoreTimestamp(longField(fields.get(13))) : null);
                frame.setSequence(fields.size() > 14 ? longField(fields.get(14)) : null);
                frame.setSampleTime(fromStoreTimestamp(record.getTimestamp()));
                frames.add(frame);
            }
        }
        catch (Exception ex)
        {
            invalidatePool(pool, ex);
            log.warn("Failed to query vibration frames from IoTDB, device={}, channel={}",
                    deviceCode, channelId, ex);
            throw new TimeSeriesStoreUnavailableException("IoTDB vibration query failed", ex);
        }
        markOperationSuccess();
        return frames;
    }

    private void markWriteSuccess()
    {
        lastSuccessfulWriteTime = new Date();
        markOperationSuccess();
    }

    private void markOperationSuccess()
    {
        available = true;
        lastSuccessfulOperationTime = new Date();
        lastError = null;
    }

    private TimeSeriesStoreUnavailableException unavailable()
    {
        return new TimeSeriesStoreUnavailableException(enabled
                ? "IoTDB time-series storage is unavailable"
                : "IoTDB time-series storage is disabled");
    }

    private void initializeSchema(ITableSessionPool pool) throws IoTDBConnectionException, StatementExecutionException
    {
        try (ITableSession session = pool.getSession())
        {
            session.executeNonQueryStatement("USE " + database);
            session.executeNonQueryStatement("CREATE TABLE IF NOT EXISTS " + TELEMETRY_TABLE + " ("
                    + "\"org_code\" STRING TAG,"
                    + "\"line_code\" STRING TAG,"
                    + "\"device_code\" STRING TAG,"
                    + "\"point_code\" STRING TAG,"
                    + "\"metric_code\" STRING TAG,"
                    + "\"signal_type\" STRING ATTRIBUTE,"
                    + "\"unit\" STRING ATTRIBUTE,"
                    + "\"channel_id\" INT32 FIELD,"
                    + "\"value\" DOUBLE FIELD,"
                    + "\"quality\" STRING FIELD,"
                    + "\"receive_time\" TIMESTAMP FIELD,"
                    + "\"sequence\" INT64 FIELD)");
            session.executeNonQueryStatement("ALTER TABLE " + TELEMETRY_TABLE
                    + " SET PROPERTIES TTL=" + ttlMillis(telemetryTtlDays));
            session.executeNonQueryStatement("CREATE TABLE IF NOT EXISTS " + FRAME_TABLE + " ("
                    + "\"device_code\" STRING TAG,"
                    + "\"point_code\" STRING TAG,"
                    + "\"axis\" STRING ATTRIBUTE,"
                    + "\"unit\" STRING ATTRIBUTE,"
                    + "\"channel_id\" INT32 FIELD,"
                    + "\"sample_rate\" INT32 FIELD,"
                    + "\"sample_count\" INT32 FIELD,"
                    + "\"waveform\" BLOB FIELD,"
                    + "\"spectrum\" BLOB FIELD,"
                    + "\"freq_step\" DOUBLE FIELD,"
                    + "\"fft_size\" INT32 FIELD,"
                    + "\"rpm\" DOUBLE FIELD,"
                    + "\"load\" DOUBLE FIELD,"
                    + "\"fault_type\" STRING FIELD,"
                    + "\"fault_size\" DOUBLE FIELD,"
                    + "\"quality\" STRING FIELD,"
                    + "\"receive_time\" TIMESTAMP FIELD,"
                    + "\"sequence\" INT64 FIELD)");
            session.executeNonQueryStatement("ALTER TABLE " + FRAME_TABLE
                    + " SET PROPERTIES TTL=" + ttlMillis(frameTtlDays));
            session.executeNonQueryStatement("CREATE TABLE IF NOT EXISTS " + DIAGNOSIS_TABLE + " ("
                    + "\"record_id\" STRING TAG,"
                    + "\"device_code\" STRING TAG,"
                    + "\"point_key\" STRING TAG,"
                    + "\"analysis_mode\" STRING TAG,"
                    + "\"model_version\" STRING TAG,"
                    + "\"batch_id\" INT64 FIELD,\"task_id\" INT64 FIELD,\"point_id\" INT64 FIELD,"
                    + "\"channel_id\" INT32 FIELD,\"model_release_id\" INT64 FIELD,"
                    + "\"source_file\" STRING FIELD,\"sample_rate\" DOUBLE FIELD,"
                    + "\"diagnosis_result\" STRING FIELD,\"closed_prediction\" STRING FIELD,"
                    + "\"confidence\" DOUBLE FIELD,\"health_index\" INT32 FIELD,"
                    + "\"risk_level\" STRING FIELD,\"alarm_level\" STRING FIELD,"
                    + "\"diagnosis_detail\" STRING FIELD,\"decision_reason\" STRING FIELD,"
                    + "\"unknown_ratio\" DOUBLE FIELD,\"segment_consistency\" DOUBLE FIELD,"
                    + "\"mean_mahalanobis\" DOUBLE FIELD,\"mean_entropy\" DOUBLE FIELD,"
                    + "\"rms\" DOUBLE FIELD,\"peak\" DOUBLE FIELD,"
                    + "\"top_probabilities\" STRING FIELD,\"evidence\" STRING FIELD,"
                    + "\"timeseries_ref\" STRING FIELD,\"sample_time\" TIMESTAMP FIELD,"
                    + "\"update_time\" TIMESTAMP FIELD,\"remark\" STRING FIELD)");
            session.executeNonQueryStatement("ALTER TABLE " + DIAGNOSIS_TABLE
                    + " SET PROPERTIES TTL=" + ttlMillis(diagnosisTtlDays));
        }
    }

    private void ensureDatabase(ITableSessionPool pool)
            throws IoTDBConnectionException, StatementExecutionException
    {
        try (ITableSession session = pool.getSession())
        {
            session.executeNonQueryStatement("CREATE DATABASE IF NOT EXISTS " + database);
        }
    }

    private ITableSessionPool buildPool(String targetDatabase)
    {
        TableSessionPoolBuilder builder = new TableSessionPoolBuilder()
                .nodeUrls(parseNodeUrls(nodeUrls))
                .user(username)
                .password(password)
                .queryTimeoutInMs(Math.max(500L, queryTimeoutMs))
                .fetchSize(Math.max(1, fetchSize))
                .maxSize(Math.max(1, sessionPoolSize))
                .connectionTimeoutInMs(Math.max(500, connectionTimeoutMs))
                .waitToGetSessionTimeoutInMs(Math.max(500L, waitSessionTimeoutMs))
                .maxRetryCount(Math.max(0, maxRetryCount))
                .retryIntervalInMs(Math.max(0L, retryIntervalMs))
                .enableIoTDBRpcCompression(rpcCompression)
                .enableRedirection(redirection)
                .enableAutoFetch(autoFetchNodes)
                .useSSL(useSsl);
        if (targetDatabase != null && !targetDatabase.isBlank())
        {
            builder.database(targetDatabase);
        }
        if (useSsl)
        {
            builder.trustStore(trustStore).trustStorePwd(trustStorePassword);
        }
        return builder.build();
    }

    private List<String> parseNodeUrls(String raw)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    private void validateConfiguration()
    {
        if (database == null || !database.matches("[A-Za-z_][A-Za-z0-9_]*"))
        {
            throw new IllegalArgumentException("Invalid IoTDB database name: " + database);
        }
        if (parseNodeUrls(nodeUrls).isEmpty())
        {
            throw new IllegalArgumentException("IoTDB node-urls must not be empty");
        }
        for (String nodeUrl : parseNodeUrls(nodeUrls))
        {
            int separator = nodeUrl.lastIndexOf(':');
            if (separator <= 0 || separator == nodeUrl.length() - 1)
            {
                throw new IllegalArgumentException("Invalid IoTDB node URL: " + nodeUrl);
            }
            try
            {
                int port = Integer.parseInt(nodeUrl.substring(separator + 1));
                if (port < 1 || port > 65535)
                {
                    throw new IllegalArgumentException("Invalid IoTDB node port: " + nodeUrl);
                }
            }
            catch (NumberFormatException ex)
            {
                throw new IllegalArgumentException("Invalid IoTDB node URL: " + nodeUrl, ex);
            }
        }
        if (username == null || username.isBlank())
        {
            throw new IllegalArgumentException("IoTDB username must not be empty");
        }
        if (!"ms".equalsIgnoreCase(timestampPrecision)
                && !"us".equalsIgnoreCase(timestampPrecision)
                && !"ns".equalsIgnoreCase(timestampPrecision))
        {
            throw new IllegalArgumentException("Unsupported IoTDB timestamp precision: " + timestampPrecision);
        }
        if (telemetryTtlDays <= 0 || frameTtlDays <= 0 || diagnosisTtlDays <= 0)
        {
            throw new IllegalArgumentException("IoTDB telemetry/frame/diagnosis TTL days must be greater than zero");
        }
        if (useSsl && (trustStore == null || trustStore.isBlank()))
        {
            throw new IllegalArgumentException("IoTDB trust-store is required when SSL is enabled");
        }
    }

    private ITableSessionPool requireAvailablePool()
    {
        ITableSessionPool pool = availablePool();
        if (pool == null)
        {
            requestConnection();
            throw unavailable();
        }
        return pool;
    }

    private ITableSessionPool availablePool()
    {
        ITableSessionPool pool = sessionPool;
        return available && pool != null ? pool : null;
    }

    private synchronized void replacePool(ITableSessionPool replacement)
    {
        ITableSessionPool previous = sessionPool;
        sessionPool = replacement;
        available = true;
        if (previous != null && previous != replacement)
        {
            previous.close();
        }
    }

    private synchronized ITableSessionPool detachPool()
    {
        ITableSessionPool previous = sessionPool;
        sessionPool = null;
        available = false;
        return previous;
    }

    private void invalidatePool(ITableSessionPool failedPool, Exception exception)
    {
        boolean invalidatedCurrentPool = false;
        synchronized (this)
        {
            if (sessionPool == failedPool)
            {
                sessionPool = null;
                available = false;
                invalidatedCurrentPool = true;
            }
        }
        try
        {
            failedPool.close();
        }
        catch (Exception closeError)
        {
            log.debug("Failed to close invalid IoTDB session pool.", closeError);
        }
        recordFailure(exception, invalidatedCurrentPool);
        if (invalidatedCurrentPool)
        {
            requestConnection();
        }
    }

    private void recordFailure(Exception exception)
    {
        recordFailure(exception, true);
    }

    private void recordFailure(Exception exception, boolean markUnavailable)
    {
        if (markUnavailable)
        {
            available = false;
        }
        failureCount.incrementAndGet();
        lastFailureTime = new Date();
        lastError = rootMessage(exception);
    }

    private String currentState()
    {
        if (!enabled)
        {
            return "DISABLED";
        }
        if (available)
        {
            return "AVAILABLE";
        }
        return connectionAttemptInProgress.get() ? "CONNECTING" : "UNAVAILABLE";
    }

    private String rootMessage(Throwable error)
    {
        Throwable current = error;
        while (current != null && current.getCause() != null)
        {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        if (message == null || message.isBlank())
        {
            message = error == null ? "Unknown IoTDB error" : error.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private long ttlMillis(int ttlDays)
    {
        return Math.max(1L, ttlDays) * 24L * 60L * 60L * 1000L;
    }

    private long toStoreTimestamp(Date value)
    {
        long ts = value == null ? System.currentTimeMillis() : value.getTime();
        if ("ns".equalsIgnoreCase(timestampPrecision))
        {
            return ts * 1_000_000L;
        }
        if ("us".equalsIgnoreCase(timestampPrecision))
        {
            return ts * 1_000L;
        }
        return ts;
    }

    private Date fromStoreTimestamp(long value)
    {
        if ("ns".equalsIgnoreCase(timestampPrecision))
        {
            return new Date(value / 1_000_000L);
        }
        if ("us".equalsIgnoreCase(timestampPrecision))
        {
            return new Date(value / 1_000L);
        }
        return new Date(value);
    }

    private String literal(String value)
    {
        return value == null ? "" : value.replace("'", "''");
    }

    private String safeText(String value)
    {
        return value == null ? "" : value;
    }

    private String resolvePointCode(TelemetryEnvelope envelope)
    {
        if (envelope.getPointCode() != null && !envelope.getPointCode().trim().isEmpty())
        {
            return envelope.getPointCode();
        }
        return "CH-" + String.valueOf(envelope.getChannelId() == null ? 0 : envelope.getChannelId());
    }

    private String resolvePointCode(VibrationFrameEnvelope envelope)
    {
        if (envelope.getPointCode() != null && !envelope.getPointCode().trim().isEmpty())
        {
            return envelope.getPointCode();
        }
        return "CH-" + String.valueOf(envelope.getChannelId() == null ? 0 : envelope.getChannelId());
    }

    private String diagnosisPointKey(DiagnosisResultSnapshot result)
    {
        if (result.getPointId() != null)
        {
            return "POINT-" + result.getPointId();
        }
        return "CH-" + (result.getChannelId() == null ? 0 : result.getChannelId());
    }

    private void addValue(Tablet tablet, String column, Object value)
    {
        if (value != null)
        {
            tablet.addValue(column, 0, value);
        }
    }

    private Double decimalValue(java.math.BigDecimal value)
    {
        return value == null ? null : value.doubleValue();
    }

    private Long storeTimestamp(Date value)
    {
        return value == null ? null : toStoreTimestamp(value);
    }

    private java.math.BigDecimal decimalField(Field field)
    {
        Double value = doubleField(field);
        return value == null ? null : java.math.BigDecimal.valueOf(value);
    }

    private DiagnosisResultSnapshot mapDiagnosis(RowRecord row)
    {
        List<Field> fields = row.getFields();
        DiagnosisResultSnapshot result = new DiagnosisResultSnapshot();
        result.setRecordId(parseLong(stringField(field(fields, 0))));
        result.setBatchId(longField(field(fields, 1)));
        result.setTaskId(longField(field(fields, 2)));
        result.setPointId(longField(field(fields, 3)));
        result.setChannelId(intField(field(fields, 4)));
        result.setModelReleaseId(longField(field(fields, 5)));
        result.setSourceFile(stringField(field(fields, 6)));
        result.setSampleRate(doubleField(field(fields, 7)));
        result.setDiagnosisResult(stringField(field(fields, 8)));
        result.setClosedPrediction(stringField(field(fields, 9)));
        result.setConfidence(decimalField(field(fields, 10)));
        result.setHealthIndex(intField(field(fields, 11)));
        result.setRiskLevel(stringField(field(fields, 12)));
        result.setAlarmLevel(stringField(field(fields, 13)));
        result.setDiagnosisDetail(stringField(field(fields, 14)));
        result.setDecisionReason(stringField(field(fields, 15)));
        result.setUnknownRatio(decimalField(field(fields, 16)));
        result.setSegmentConsistency(decimalField(field(fields, 17)));
        result.setMeanMahalanobis(decimalField(field(fields, 18)));
        result.setMeanEntropy(decimalField(field(fields, 19)));
        result.setRms(doubleField(field(fields, 20)));
        result.setPeak(doubleField(field(fields, 21)));
        result.setTopProbabilities(stringField(field(fields, 22)));
        result.setEvidence(stringField(field(fields, 23)));
        result.setTimeseriesRef(stringField(field(fields, 24)));
        result.setSampleTime(dateField(field(fields, 25)));
        result.setUpdateTime(dateField(field(fields, 26)));
        result.setRemark(stringField(field(fields, 27)));
        result.setDeviceCode(stringField(field(fields, 28)));
        result.setAnalysisMode(stringField(field(fields, 29)));
        result.setModelVersion(stringField(field(fields, 30)));
        result.setCreateTime(fromStoreTimestamp(row.getTimestamp()));
        return result;
    }

    private Field field(List<Field> fields, int index)
    {
        return index < fields.size() ? fields.get(index) : null;
    }

    private Date dateField(Field field)
    {
        Long value = longField(field);
        return value == null ? null : fromStoreTimestamp(value);
    }

    private Long parseLong(String value)
    {
        try
        {
            return value == null ? null : Long.valueOf(value);
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    private Double doubleField(Field field)
    {
        return field == null || field.getDataType() == null ? null : field.getDoubleV();
    }

    private Integer intField(Field field)
    {
        return field == null || field.getDataType() == null ? null : field.getIntV();
    }

    private Long longField(Field field)
    {
        return field == null || field.getDataType() == null ? null : field.getLongV();
    }

    private String stringField(Field field)
    {
        return field == null || field.getDataType() == null ? null : field.getStringValue();
    }

    private List<Double> blobField(Field field)
    {
        if (field == null || field.getDataType() == null)
        {
            return new ArrayList<>();
        }
        Binary binary = field.getBinaryV();
        return binary == null ? new ArrayList<>() : TimeSeriesFrameCodec.decode(binary.getValues());
    }
}
