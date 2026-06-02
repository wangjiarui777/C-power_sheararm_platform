package com.ruoyi.sensor.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import io.netty.channel.Channel;
import org.jtransforms.fft.DoubleFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ruoyi.sensor.domain.dto.SensorSampleDto;
import com.ruoyi.sensor.domain.vo.ChannelRealtimeVo;
import com.ruoyi.sensor.domain.vo.SensorFeatureVo;

@Service
public class SensorSignalComputeService
{
    private static final Logger log = LoggerFactory.getLogger(SensorSignalComputeService.class);
    private static final double VIBRATION_THRESHOLD = 0.20D;
    private static final double TEMPERATURE_THRESHOLD = 37.5D;

    private final SensorStorageService storageService;
    private final SensorWebSocketPushService pushService;

    public SensorSignalComputeService(SensorStorageService storageService, SensorWebSocketPushService pushService)
    {
        this.storageService = storageService;
        this.pushService = pushService;
    }

    public void computeAndPersist(byte[] payload, Channel channel)
    {
        SensorPacket packet = decodePayload(payload);
        if (packet == null)
        {
            log.warn("decode payload failed, length={}", payload == null ? 0 : payload.length);
            return;
        }

        double rms = calcRms(packet.waveform);
        double peak = calcPeak(packet.waveform);

        double[] fftInput = new double[packet.waveform.length * 2];
        System.arraycopy(packet.waveform, 0, fftInput, 0, packet.waveform.length);
        DoubleFFT_1D fft = new DoubleFFT_1D(packet.waveform.length);
        fft.realForwardFull(fftInput);

        List<Double> freqAmplitude = new ArrayList<>();
        for (int i = 0; i + 1 < fftInput.length; i += 2)
        {
            double real = fftInput[i];
            double imag = fftInput[i + 1];
            freqAmplitude.add(Math.sqrt(real * real + imag * imag));
        }

        boolean alarm = rms >= VIBRATION_THRESHOLD || peak >= TEMPERATURE_THRESHOLD;
        String alarmMessage = alarm ? String.format("Threshold exceeded, RMS=%.4f, Peak=%.4f", rms, peak) : null;

        ChannelRealtimeVo featureVo = new ChannelRealtimeVo();
        featureVo.setDeviceCode(packet.deviceCode);
        featureVo.setChannelId(packet.channelId);
        featureVo.setSampleTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(packet.sampleTime), ZoneId.systemDefault()));
        featureVo.setRms(rms);
        featureVo.setPeak(peak);
        featureVo.setAlarm(alarm);
        featureVo.setAlarmMessage(alarmMessage);

        SensorFeatureVo storageFeatureVo = new SensorFeatureVo();
        storageFeatureVo.setDeviceCode(featureVo.getDeviceCode());
        storageFeatureVo.setChannelId(featureVo.getChannelId());
        storageFeatureVo.setSampleTime(featureVo.getSampleTime());
        storageFeatureVo.setRms(featureVo.getRms());
        storageFeatureVo.setPeak(featureVo.getPeak());
        storageFeatureVo.setAlarm(featureVo.getAlarm());
        storageFeatureVo.setAlarmMessage(featureVo.getAlarmMessage());

        SensorSampleDto sample = new SensorSampleDto(packet.deviceCode, packet.sampleTime, packet.sampleRate, packet.waveform);
        storageService.asyncSave(sample, storageFeatureVo, freqAmplitude, alarm, packet.channelId);
        pushService.pushFeature(featureVo);
    }

    private SensorPacket decodePayload(byte[] payload)
    {
        try
        {
            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 1)
            {
                return null;
            }

            int codeLength = Byte.toUnsignedInt(buffer.get());
            if (buffer.remaining() < codeLength + Long.BYTES + Integer.BYTES + Integer.BYTES)
            {
                return null;
            }

            byte[] codeBytes = new byte[codeLength];
            buffer.get(codeBytes);
            String deviceCode = new String(codeBytes, StandardCharsets.UTF_8);

            int channelId = 1;
            if (buffer.remaining() >= Integer.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES)
            {
                buffer.mark();
                int maybeChannelId = buffer.getInt();
                long maybeSampleTime = buffer.getLong();
                int maybeSampleRate = buffer.getInt();
                int maybeWaveCount = buffer.getInt();
                if (maybeChannelId > 0 && maybeWaveCount >= 0 && buffer.remaining() >= (long) maybeWaveCount * Double.BYTES)
                {
                    double[] waveform = new double[maybeWaveCount];
                    for (int i = 0; i < maybeWaveCount; i++)
                    {
                        waveform[i] = buffer.getDouble();
                    }
                    return new SensorPacket(deviceCode, maybeChannelId, maybeSampleTime, maybeSampleRate, waveform);
                }
                buffer.reset();
            }

            long sampleTime = buffer.getLong();
            int sampleRate = buffer.getInt();
            int waveCount = buffer.getInt();
            if (waveCount < 0 || buffer.remaining() < (long) waveCount * Double.BYTES)
            {
                return null;
            }

            double[] waveform = new double[waveCount];
            for (int i = 0; i < waveCount; i++)
            {
                waveform[i] = buffer.getDouble();
            }
            return new SensorPacket(deviceCode, channelId, sampleTime, sampleRate, waveform);
        }
        catch (Exception e)
        {
            log.error("decode payload failed", e);
            return null;
        }
    }

    private double calcRms(double[] values)
    {
        if (values == null || values.length == 0)
        {
            return 0D;
        }
        double sum = 0D;
        for (double v : values)
        {
            sum += v * v;
        }
        return Math.sqrt(sum / values.length);
    }

    private double calcPeak(double[] values)
    {
        if (values == null || values.length == 0)
        {
            return 0D;
        }
        double peak = 0D;
        for (double v : values)
        {
            peak = Math.max(peak, Math.abs(v));
        }
        return peak;
    }

    private static class SensorPacket
    {
        private final String deviceCode;
        private final Integer channelId;
        private final long sampleTime;
        private final int sampleRate;
        private final double[] waveform;

        private SensorPacket(String deviceCode, Integer channelId, long sampleTime, int sampleRate, double[] waveform)
        {
            this.deviceCode = deviceCode;
            this.channelId = channelId;
            this.sampleTime = sampleTime;
            this.sampleRate = sampleRate;
            this.waveform = waveform;
        }
    }
}
