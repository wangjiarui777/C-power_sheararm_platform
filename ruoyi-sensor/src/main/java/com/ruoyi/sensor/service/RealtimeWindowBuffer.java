package com.ruoyi.sensor.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.sensor.domain.dto.VibrationCsvRow;
import com.ruoyi.sensor.domain.entity.PhmMeasurePointEntity;
import com.ruoyi.sensor.domain.entity.RealtimeDiagnosisPolicyEntity;
import com.ruoyi.sensor.mapper.PhmMeasurePointMapper;
import com.ruoyi.sensor.mapper.RealtimeDiagnosisPolicyMapper;
import io.micrometer.core.instrument.Counter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RealtimeWindowBuffer implements RealtimeDiagnosisHook
{
    private final RealtimeDiagnosisPolicyMapper policyMapper;
    private final PhmMeasurePointMapper pointMapper;
    private final RealtimeDiagnosisDispatchService dispatchService;
    private final boolean enabled;
    private final int maxBufferSamples;
    private final Counter completed;
    private final Counter dropped;
    private final Counter evicted;
    private final Map<String, State> states = new HashMap<>();

    public RealtimeWindowBuffer(RealtimeDiagnosisPolicyMapper policyMapper, PhmMeasurePointMapper pointMapper,
        RealtimeDiagnosisDispatchService dispatchService, io.micrometer.core.instrument.MeterRegistry meterRegistry,
        @Value("${sensor.diagnosis.realtime.enabled:false}") boolean enabled,
        @Value("${sensor.diagnosis.realtime.max-buffer-samples:2000000}") int maxBufferSamples)
    {
        this.policyMapper = policyMapper; this.pointMapper = pointMapper; this.dispatchService = dispatchService; this.enabled = enabled;
        this.maxBufferSamples = Math.max(262144, maxBufferSamples);
        this.completed = meterRegistry.counter("phm.realtime_diagnosis.windows.completed");
        this.dropped = meterRegistry.counter("phm.realtime_diagnosis.windows.dropped");
        this.evicted = meterRegistry.counter("phm.realtime_diagnosis.windows.buffer_evicted");
    }

    @Override
    public synchronized void onSamples(String deviceCode, List<VibrationCsvRow> rows)
    {
        if (!enabled || deviceCode == null || rows == null || rows.isEmpty()) return;
        List<RealtimeDiagnosisPolicyEntity> policies = policyMapper.selectList(new LambdaQueryWrapper<RealtimeDiagnosisPolicyEntity>()
            .eq(RealtimeDiagnosisPolicyEntity::getEnabled, true));
        if (policies.isEmpty()) return;
        List<Long> pointIds = policies.stream().map(RealtimeDiagnosisPolicyEntity::getPointId).distinct().toList();
        Map<Long, PhmMeasurePointEntity> points = new HashMap<>();
        for (PhmMeasurePointEntity point : pointMapper.selectBatchIds(pointIds)) points.put(point.getId(), point);
        for (VibrationCsvRow row : rows)
        {
            Double value = row.getRecord().getDeTime() != null ? row.getRecord().getDeTime() : row.getRecord().getFaultSize();
            if (value == null || !Double.isFinite(value)) continue;
            for (RealtimeDiagnosisPolicyEntity policy : policies)
            {
                PhmMeasurePointEntity point = points.get(policy.getPointId());
                if (point == null || !deviceCode.equals(point.getDeviceCode()) || !java.util.Objects.equals(point.getChannelId(), row.getChannelId())) continue;
                String key = deviceCode + ":" + row.getChannelId() + ":" + policy.getId();
                State state = states.computeIfAbsent(key, ignored -> new State());
                state.values.addLast(value);
                while (state.values.size() > maxBufferSamples)
                {
                    state.values.removeFirst();
                    evicted.increment();
                }
                int window = policy.getWindowSamples() == null ? 5120 : policy.getWindowSamples();
                int stride = policy.getStrideSamples() == null ? window : policy.getStrideSamples();
                if (state.values.size() < window) continue;
                long now = System.currentTimeMillis();
                long minInterval = (policy.getMinIntervalSeconds() == null ? 30 : policy.getMinIntervalSeconds()) * 1000L;
                if (state.lastDispatchAt > 0 && now - state.lastDispatchAt < minInterval) continue;
                List<Double> snapshot = new ArrayList<>(state.values).subList(0, window);
                try
                {
                    boolean accepted = dispatchService.enqueue(policy, deviceCode, row.getChannelId(), snapshot,
                        row.getSampleTime() == null ? new Date() : row.getSampleTime());
                    if (accepted) { state.lastDispatchAt = now; completed.increment(); }
                    else dropped.increment();
                }
                catch (Exception ignored)
                {
                    dropped.increment();
                }
                for (int i = 0; i < stride && !state.values.isEmpty(); i++) state.values.removeFirst();
            }
        }
    }

    /** Number of active device/channel/policy buffers currently held in memory. */
    public synchronized long bufferedChannels()
    {
        return states.size();
    }

    private static final class State
    {
        private final ArrayDeque<Double> values = new ArrayDeque<>();
        private long lastDispatchAt;
    }
}
