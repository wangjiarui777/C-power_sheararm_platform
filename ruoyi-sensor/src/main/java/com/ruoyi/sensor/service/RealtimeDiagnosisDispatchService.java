package com.ruoyi.sensor.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.sensor.domain.entity.InferenceTaskEntity;
import com.ruoyi.sensor.domain.entity.ModelReleaseEntity;
import com.ruoyi.sensor.domain.entity.RealtimeDiagnosisPolicyEntity;
import com.ruoyi.sensor.mapper.InferenceTaskMapper;
import com.ruoyi.sensor.mapper.ModelReleaseMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** Durable, time-bounded dispatch from completed windows to model workers. */
@Service
public class RealtimeDiagnosisDispatchService
{
    public static final String GEAR_STREAM = "monitoring:diagnosis:job:gear";
    public static final String BEARING_STREAM = "monitoring:diagnosis:job:bearing";
    private static final String GEAR_GROUP = "realtime-infer-gear";
    private static final String BEARING_GROUP = "realtime-infer-bearing";

    private final StringRedisTemplate redisTemplate;
    private final InferenceTaskMapper taskMapper;
    private final ModelReleaseMapper modelReleaseMapper;
    private final PhmService phmService;
    private final SensorWebSocketPushService websocket;
    private final RestClient gearClient;
    private final RestClient bearingClient;
    private final String internalToken;
    private final long deadlineSeconds;
    private final int maxAttempts;
    private final long pendingClaimIdleMs;
    private final long queueMaxLength;
    private final String gearModelVersion;
    private final String bearingModelVersion;
    private final int batchSize;
    private final String consumerName = "rt-" + UUID.randomUUID();
    private final Counter dispatched;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter expired;
    private final AtomicLong gearQueueDepth = new AtomicLong();
    private final AtomicLong bearingQueueDepth = new AtomicLong();

    public RealtimeDiagnosisDispatchService(StringRedisTemplate redisTemplate,
        InferenceTaskMapper taskMapper, ModelReleaseMapper modelReleaseMapper,
        PhmService phmService, SensorWebSocketPushService websocket,
        @Value("${sensor.inference.gear-url:}") String gearUrl,
        @Value("${sensor.inference.bearing-url:}") String bearingUrl,
        @Value("${sensor.inference.internal-token:}") String internalToken,
        @Value("${sensor.diagnosis.realtime.job-deadline-seconds:10}") long deadlineSeconds,
        @Value("${sensor.diagnosis.realtime.max-attempts:2}") int maxAttempts,
        @Value("${sensor.diagnosis.realtime.pending-claim-idle-ms:30000}") long pendingClaimIdleMs,
        @Value("${sensor.diagnosis.realtime.queue-max-length:10000}") long queueMaxLength,
        @Value("${sensor.inference.gear-model-version:gear-unregistered}") String gearModelVersion,
        @Value("${sensor.inference.bearing-model-version:bearing-unregistered}") String bearingModelVersion,
        @Value("${sensor.diagnosis.realtime.coalesce-batch-size:8}") int batchSize,
        MeterRegistry meterRegistry)
    {
        this.redisTemplate = redisTemplate;
        this.taskMapper = taskMapper;
        this.modelReleaseMapper = modelReleaseMapper;
        this.phmService = phmService;
        this.websocket = websocket;
        this.internalToken = internalToken;
        this.deadlineSeconds = Math.max(1L, deadlineSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.pendingClaimIdleMs = Math.max(1000L, pendingClaimIdleMs);
        this.queueMaxLength = Math.max(100L, queueMaxLength);
        this.gearModelVersion = gearModelVersion;
        this.bearingModelVersion = bearingModelVersion;
        this.batchSize = Math.max(1, Math.min(32, batchSize));
        this.gearClient = client(gearUrl, 4000);
        this.bearingClient = client(bearingUrl, 4000);
        this.dispatched = meterRegistry.counter("phm.realtime_diagnosis.dispatched");
        this.succeeded = meterRegistry.counter("phm.realtime_diagnosis.succeeded");
        this.failed = meterRegistry.counter("phm.realtime_diagnosis.failed");
        this.expired = meterRegistry.counter("phm.realtime_diagnosis.expired");
        io.micrometer.core.instrument.Gauge.builder("phm.realtime_diagnosis.gear_queue", gearQueueDepth, AtomicLong::doubleValue).register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("phm.realtime_diagnosis.bearing_queue", bearingQueueDepth, AtomicLong::doubleValue).register(meterRegistry);
    }

    public boolean enqueue(RealtimeDiagnosisPolicyEntity policy, String deviceCode,
        Integer channelId, List<Double> samples, Date sampleTime)
    {
        if (policy == null || samples == null || samples.isEmpty()) return false;
        String modelVersion = resolveModelVersion(policy);
        Date now = new Date();
        Date deadline = new Date(now.getTime() + deadlineSeconds * 1000L);
        String windowId = UUID.randomUUID().toString();
        String idempotency = "rt:" + policy.getPointId() + ":" + sampleTime.getTime()
            + ":" + samples.size() + ":" + (modelVersion == null ? "runtime" : modelVersion);
        InferenceTaskEntity existing = taskMapper.selectOne(new LambdaQueryWrapper<InferenceTaskEntity>()
            .eq(InferenceTaskEntity::getIdempotencyKey, idempotency).last("limit 1"));
        if (existing != null) return true;

        InferenceTaskEntity task = new InferenceTaskEntity();
        task.setRequestId(UUID.randomUUID().toString());
        task.setIdempotencyKey(idempotency);
        task.setDeviceCode(deviceCode);
        task.setPointId(policy.getPointId());
        task.setChannelId(channelId);
        task.setModelType(policy.getModelType());
        task.setRequestedModelVersion(modelVersion);
        task.setInputType("REALTIME_WINDOW");
        task.setSourceType("REALTIME");
        task.setWindowId(windowId);
        task.setStatus("QUEUED");
        task.setAttemptCount(0);
        task.setCreateTime(now);
        task.setQueuedAt(now);
        task.setDeadlineAt(deadline);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("windowId", windowId);
        metadata.put("pointId", policy.getPointId());
        metadata.put("modelType", policy.getModelType());
        metadata.put("modelVersion", modelVersion);
        metadata.put("sampleCount", samples.size());
        task.setInputJson(JSON.toJSONString(metadata));
        taskMapper.insert(task);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", task.getRequestId());
        payload.put("taskId", task.getId());
        payload.put("windowId", windowId);
        payload.put("modelType", policy.getModelType());
        payload.put("modelVersion", modelVersion);
        payload.put("deviceCode", deviceCode);
        payload.put("pointId", policy.getPointId());
        payload.put("channelId", channelId);
        payload.put("sampleRate", 5120D);
        payload.put("sampleTime", sampleTime);
        payload.put("deadlineAt", deadline);
        payload.put("rawSignal", samples);
        try
        {
            String stream = "bearing".equals(policy.getModelType()) ? BEARING_STREAM : GEAR_STREAM;
            addToQueue(stream, JSON.toJSONString(payload), "0");
            dispatched.increment();
            return true;
        }
        catch (Exception exception)
        {
            task.setStatus("DROPPED");
            task.setErrorCode("QUEUE_UNAVAILABLE");
            task.setErrorMessage(exception.getMessage());
            task.setFinishTime(new Date());
            task.setUpdateTime(new Date());
            taskMapper.updateById(task);
            failed.increment();
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${sensor.diagnosis.realtime.dispatch-poll-ms:250}")
    public void consumeGear()
    {
        consume(GEAR_STREAM, GEAR_GROUP, false);
    }

    @Scheduled(fixedDelayString = "${sensor.diagnosis.realtime.dispatch-poll-ms:250}")
    public void consumeBearing()
    {
        consume(BEARING_STREAM, BEARING_GROUP, true);
    }

    /** Recover deliveries left in a consumer's Pending Entries List after a process restart. */
    @Scheduled(fixedDelayString = "${sensor.diagnosis.realtime.pending-recovery-ms:1000}")
    public void recoverPending()
    {
        recoverPending(GEAR_STREAM, GEAR_GROUP, false);
        recoverPending(BEARING_STREAM, BEARING_GROUP, true);
    }

    public long queueDepth(String modelType)
    {
        return "bearing".equals(modelType) ? bearingQueueDepth.get() : gearQueueDepth.get();
    }

    public long pendingCount(String modelType)
    {
        String stream = "bearing".equals(modelType) ? BEARING_STREAM : GEAR_STREAM;
        String group = "bearing".equals(modelType) ? BEARING_GROUP : GEAR_GROUP;
        try
        {
            var summary = redisTemplate.opsForStream().pending(stream, group);
            return summary == null ? 0L : summary.getTotalPendingMessages();
        }
        catch (Exception ignored)
        {
            return 0L;
        }
    }

    private void recoverPending(String stream, String group, boolean bearing)
    {
        ensureGroup(stream, group);
        try
        {
            var pending = redisTemplate.opsForStream().pending(stream, group, Range.unbounded(), 8);
            if (pending == null || pending.isEmpty()) return;
            List<RecordId> reclaim = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().toMillis() >= pendingClaimIdleMs)
                .map(PendingMessage::getId)
                .toList();
            if (reclaim.isEmpty()) return;
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .claim(stream, group, consumerName, Duration.ofMillis(pendingClaimIdleMs), reclaim.toArray(new RecordId[0]));
            if (records != null)
            {
                for (MapRecord<String, Object, Object> record : records) handle(stream, group, record, bearing);
            }
        }
        catch (Exception ignored)
        {
            // Redis outages must not interrupt frame ingestion; the next scheduled pass retries recovery.
        }
    }

    private void consume(String stream, String group, boolean bearing)
    {
        ensureGroup(stream, group);
        List<MapRecord<String, Object, Object>> records;
        try
        {
            records = redisTemplate.opsForStream().read(
                Consumer.from(group, consumerName),
                StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(100)),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        }
        catch (Exception exception)
        {
            return;
        }
        AtomicLong gauge = bearing ? bearingQueueDepth : gearQueueDepth;
        gauge.set(redisTemplate.opsForStream().size(stream) == null ? 0L : redisTemplate.opsForStream().size(stream));
        if (records == null) return;
        handleBatch(stream, group, records, bearing);
    }

    @SuppressWarnings("unchecked")
    private void handleBatch(String stream, String group, List<MapRecord<String, Object, Object>> records, boolean bearing)
    {
        List<DispatchItem> items = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records)
        {
            String raw = String.valueOf(record.getValue().get("payload"));
            try
            {
                Map<String, Object> payload = JSON.parseObject(raw, Map.class);
                Long taskId = number(payload.get("taskId"));
                InferenceTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
                if (task == null) { acknowledge(stream, group, record.getId()); continue; }
                if (task.getDeadlineAt() != null && task.getDeadlineAt().before(new Date()))
                {
                    finish(task, "EXPIRED", "DEADLINE_EXCEEDED", "实时任务超过新鲜度期限", null);
                    expired.increment(); acknowledge(stream, group, record.getId()); continue;
                }
                task.setStatus("RUNNING"); task.setStartTime(new Date());
                task.setAttemptCount((task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1);
                task.setUpdateTime(new Date()); taskMapper.updateById(task);
                items.add(new DispatchItem(record, raw, payload, task, group));
            }
            catch (Exception ignored)
            {
                acknowledge(stream, group, record.getId());
            }
        }
        if (items.isEmpty()) return;
        try
        {
            List<Map<String, Object>> payloads = items.stream().map(item -> item.payload).toList();
            RestClient client = bearing ? bearingClient : gearClient;
            Map<String, Object> response = client.post().uri("/internal/infer/batch")
                .header("X-Internal-Token", internalToken)
                .body(Map.of("items", payloads)).retrieve().body(Map.class);
            List<Map<String, Object>> results = response == null ? List.of() : (List<Map<String, Object>>) response.getOrDefault("results", List.of());
            Map<String, Map<String, Object>> byRequest = new LinkedHashMap<>();
            for (Map<String, Object> result : results)
            {
                if (result != null && result.get("requestId") != null) byRequest.put(String.valueOf(result.get("requestId")), result);
            }
            for (int index = 0; index < items.size(); index++)
            {
                DispatchItem item = items.get(index);
                try
                {
                    Map<String, Object> result = byRequest.get(item.task.getRequestId());
                    if (result == null && index < results.size()) result = results.get(index);
                    if (result == null || !Boolean.TRUE.equals(result.get("success")))
                    {
                        retryOrFail(stream, item, result == null ? "empty inference response" : String.valueOf(result.get("errorMessage")));
                    }
                    else
                    {
                        Map<String, Object> diagnosis = (Map<String, Object>) result.get("data");
                        if (diagnosis == null) retryOrFail(stream, item, "inference result data is empty");
                        else completeSuccess(stream, group, item, diagnosis);
                    }
                }
                catch (Exception itemError)
                {
                    retryOrFail(stream, item, itemError.getMessage());
                }
            }
        }
        catch (Exception exception)
        {
            for (DispatchItem item : items) retryOrFail(stream, item, exception.getMessage());
        }
    }

    private void completeSuccess(String stream, String group, DispatchItem item, Map<String, Object> diagnosis)
    {
        diagnosis.put("taskId", item.task.getId()); diagnosis.put("requestId", item.task.getRequestId());
        diagnosis.put("sourceType", "REALTIME"); diagnosis.put("windowId", item.task.getWindowId());
        long now = System.currentTimeMillis();
        diagnosis.put("modelVersion", diagnosis.get("modelVersion") == null ? item.task.getRequestedModelVersion() : diagnosis.get("modelVersion"));
        diagnosis.put("queueDelayMs", item.task.getQueuedAt() == null ? 0L : Math.max(0L, now - item.task.getQueuedAt().getTime()));
        diagnosis.put("endToEndLatencyMs", item.task.getCreateTime() == null ? 0L : Math.max(0L, now - item.task.getCreateTime().getTime()));
        diagnosis.put("resultStatus", "VALID");
        phmService.syncDiagnosisResult(diagnosis);
        phmService.recalculateDiagnosisState(item.task.getDeviceCode());
        websocket.pushDiagnosis(diagnosis);
        finish(item.task, "SUCCEEDED", null, null, diagnosis);
        succeeded.increment(); acknowledge(stream, group, item.record.getId());
    }

    private void retryOrFail(String stream, DispatchItem item, String message)
    {
        if ((item.task.getAttemptCount() == null ? 1 : item.task.getAttemptCount()) < maxAttempts
            && (item.task.getDeadlineAt() == null || item.task.getDeadlineAt().after(new Date())))
        {
            item.task.setStatus("QUEUED"); item.task.setErrorCode("RETRYABLE");
            item.task.setErrorMessage(message); item.task.setUpdateTime(new Date()); taskMapper.updateById(item.task);
            try { addToQueue(stream, item.raw, String.valueOf(item.task.getAttemptCount())); }
            catch (Exception queueError) { finish(item.task, "DROPPED", "QUEUE_UNAVAILABLE", queueError.getMessage(), null); failed.increment(); }
        }
        else
        {
            finish(item.task, "FAILED", "INFERENCE_UNAVAILABLE", message, null); failed.increment();
        }
        acknowledge(stream, item.group, item.record.getId());
    }

    private static final class DispatchItem
    {
        private final MapRecord<String, Object, Object> record;
        private final String raw;
        private final Map<String, Object> payload;
        private final InferenceTaskEntity task;
        private final String group;

        private DispatchItem(MapRecord<String, Object, Object> record, String raw,
            Map<String, Object> payload, InferenceTaskEntity task, String group)
        {
            this.record = record; this.raw = raw; this.payload = payload; this.task = task;
            this.group = group;
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(String stream, String group, MapRecord<String, Object, Object> record, boolean bearing)
    {
        String raw = String.valueOf(record.getValue().get("payload"));
        Map<String, Object> payload;
        try { payload = JSON.parseObject(raw, Map.class); }
        catch (Exception exception) { acknowledge(stream, group, record.getId()); return; }
        Long taskId = number(payload.get("taskId"));
        InferenceTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) { acknowledge(stream, group, record.getId()); return; }
        Date deadline = task.getDeadlineAt();
        if (deadline != null && deadline.before(new Date()))
        {
            finish(task, "EXPIRED", "DEADLINE_EXCEEDED", "实时任务超过新鲜度期限", null);
            expired.increment();
            acknowledge(stream, group, record.getId());
            return;
        }
        task.setStatus("RUNNING"); task.setStartTime(new Date()); task.setAttemptCount((task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1);
        task.setUpdateTime(new Date()); taskMapper.updateById(task);
        try
        {
            RestClient client = bearing ? bearingClient : gearClient;
            Map<String, Object> response = client.post().uri("/internal/infer/batch")
                .header("X-Internal-Token", internalToken)
                .body(Map.of("items", List.of(payload))).retrieve().body(Map.class);
            List<Map<String, Object>> results = response == null ? null : (List<Map<String, Object>>) response.get("results");
            Map<String, Object> item = results == null || results.isEmpty() ? null : results.get(0);
            if (item == null || !Boolean.TRUE.equals(item.get("success")))
                throw new IllegalStateException(item == null ? "empty inference response" : String.valueOf(item.get("errorMessage")));
            Map<String, Object> diagnosis = (Map<String, Object>) item.get("data");
            if (diagnosis == null) throw new IllegalStateException("inference result data is empty");
            diagnosis.put("taskId", task.getId());
            diagnosis.put("requestId", task.getRequestId());
            diagnosis.put("sourceType", "REALTIME");
            diagnosis.put("windowId", task.getWindowId());
            diagnosis.put("resultStatus", "VALID");
            phmService.syncDiagnosisResult(diagnosis);
            phmService.recalculateDiagnosisState(task.getDeviceCode());
            websocket.pushDiagnosis(diagnosis);
            finish(task, "SUCCEEDED", null, null, diagnosis);
            succeeded.increment();
            acknowledge(stream, group, record.getId());
        }
        catch (Exception exception)
        {
            if ((task.getAttemptCount() == null ? 1 : task.getAttemptCount()) < maxAttempts
                && (task.getDeadlineAt() == null || task.getDeadlineAt().after(new Date())))
            {
                task.setStatus("QUEUED"); task.setErrorCode("RETRYABLE"); task.setErrorMessage(exception.getMessage()); task.setUpdateTime(new Date()); taskMapper.updateById(task);
                addToQueue(stream, raw, String.valueOf(task.getAttemptCount()));
            }
            else
            {
                finish(task, "FAILED", "INFERENCE_UNAVAILABLE", exception.getMessage(), null);
                failed.increment();
            }
            acknowledge(stream, group, record.getId());
        }
    }

    private void finish(InferenceTaskEntity task, String status, String code, String message, Map<String, Object> result)
    {
        task.setStatus(status); task.setErrorCode(code); task.setErrorMessage(message); task.setFinishTime(new Date()); task.setUpdateTime(new Date());
        if (result != null) task.setResultJson(JSON.toJSONString(result));
        taskMapper.updateById(task);
    }

    private void ensureGroup(String stream, String group)
    {
        try { redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group); }
        catch (Exception ignored) { }
    }

    private void addToQueue(String stream, String payload, String attempt)
    {
        redisTemplate.opsForStream().add(stream,
            Map.of("payload", payload, "attempt", attempt),
            RedisStreamCommands.XAddOptions.maxlen(queueMaxLength).approximateTrimming(true));
    }

    private void acknowledge(String stream, String group, RecordId id)
    {
        redisTemplate.opsForStream().acknowledge(stream, group, id);
    }

    private String resolveModelVersion(RealtimeDiagnosisPolicyEntity policy)
    {
        if (policy.getModelVersion() != null && !policy.getModelVersion().isBlank()) return policy.getModelVersion();
        ModelReleaseEntity active = modelReleaseMapper.selectOne(new LambdaQueryWrapper<ModelReleaseEntity>()
            .eq(ModelReleaseEntity::getModelType, policy.getModelType()).eq(ModelReleaseEntity::getStatus, "ACTIVE")
            .orderByDesc(ModelReleaseEntity::getCreateTime).last("limit 1"));
        if (active != null && active.getSemanticVersion() != null && !active.getSemanticVersion().isBlank())
            return active.getSemanticVersion();
        return "bearing".equals(policy.getModelType()) ? bearingModelVersion : gearModelVersion;
    }

    private RestClient client(String url, int timeout)
    {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout); factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(url == null || url.isBlank() ? "http://127.0.0.1:5000" : url.replaceAll("/internal/infer/?$", "")).requestFactory(factory).build();
    }

    private Long number(Object value)
    {
        return value instanceof Number ? ((Number) value).longValue() : value == null ? null : Long.valueOf(String.valueOf(value));
    }
}
