package com.ruoyi.sensor.service.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class NettyChannelFrameParser
{
    public Map<Integer, ChannelFrame> parse(byte[] payload, List<ChannelMapping> mappings)
    {
        Map<Integer, ChannelFrame> result = new LinkedHashMap<>();
        if (payload == null || mappings == null || mappings.isEmpty())
        {
            return result;
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        for (ChannelMapping mapping : mappings)
        {
            if (buffer.remaining() < 4)
            {
                break;
            }

            short vibrationRaw = buffer.getShort();
            short temperatureRaw = buffer.getShort();

            ChannelFrame frame = new ChannelFrame();
            frame.setChannelId(mapping.getChannelId());
            frame.setPhysicalIndex(mapping.getPhysicalIndex());
            frame.setVibrationValue(vibrationRaw / 1000.0);
            frame.setTemperatureValue(temperatureRaw / 100.0);
            frame.setWaveform(new double[] { vibrationRaw / 1000.0, temperatureRaw / 100.0 });
            result.put(mapping.getChannelId(), frame);
        }
        return result;
    }

    public List<ChannelMapping> defaultMappings()
    {
        List<ChannelMapping> mappings = new ArrayList<>();
        for (int i = 1; i <= 8; i++)
        {
            ChannelMapping mapping = new ChannelMapping();
            mapping.setChannelId(i);
            mapping.setPhysicalIndex("CH" + i);
            mapping.setRegisterOffset((i - 1) * 2);
            mapping.setChannelName("Channel " + i);
            mappings.add(mapping);
        }
        return mappings;
    }

    public static class ChannelMapping
    {
        private Integer channelId;
        private Integer registerOffset;
        private String physicalIndex;
        private String channelName;

        public Integer getChannelId() { return channelId; }
        public void setChannelId(Integer channelId) { this.channelId = channelId; }
        public Integer getRegisterOffset() { return registerOffset; }
        public void setRegisterOffset(Integer registerOffset) { this.registerOffset = registerOffset; }
        public String getPhysicalIndex() { return physicalIndex; }
        public void setPhysicalIndex(String physicalIndex) { this.physicalIndex = physicalIndex; }
        public String getChannelName() { return channelName; }
        public void setChannelName(String channelName) { this.channelName = channelName; }
    }

    public static class ChannelFrame
    {
        private Integer channelId;
        private String physicalIndex;
        private double vibrationValue;
        private double temperatureValue;
        private double[] waveform;

        public Integer getChannelId() { return channelId; }
        public void setChannelId(Integer channelId) { this.channelId = channelId; }
        public String getPhysicalIndex() { return physicalIndex; }
        public void setPhysicalIndex(String physicalIndex) { this.physicalIndex = physicalIndex; }
        public double getVibrationValue() { return vibrationValue; }
        public void setVibrationValue(double vibrationValue) { this.vibrationValue = vibrationValue; }
        public double getTemperatureValue() { return temperatureValue; }
        public void setTemperatureValue(double temperatureValue) { this.temperatureValue = temperatureValue; }
        public double[] getWaveform() { return waveform; }
        public void setWaveform(double[] waveform) { this.waveform = waveform; }
    }
}
