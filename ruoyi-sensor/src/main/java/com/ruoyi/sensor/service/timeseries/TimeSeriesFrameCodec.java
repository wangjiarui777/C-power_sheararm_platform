package com.ruoyi.sensor.service.timeseries;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

final class TimeSeriesFrameCodec
{
    private static final byte VERSION = 1;
    private static final byte FLAG_LZ4 = 1;
    private static final int HEADER_SIZE = 1 + 1 + 2 + Integer.BYTES + Integer.BYTES;
    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();
    private static final LZ4Compressor COMPRESSOR = LZ4.fastCompressor();
    private static final LZ4FastDecompressor DECOMPRESSOR = LZ4.fastDecompressor();

    private TimeSeriesFrameCodec()
    {
    }

    static byte[] encode(List<Double> values)
    {
        if (values == null || values.isEmpty())
        {
            return new byte[0];
        }
        byte[] raw = rawPayload(values);
        byte[] compressed = COMPRESSOR.compress(raw);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + compressed.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(VERSION);
        buffer.put(FLAG_LZ4);
        buffer.putShort((short) 0);
        buffer.putInt(values.size());
        buffer.putInt(raw.length);
        buffer.put(compressed);
        return buffer.array();
    }

    static List<Double> decode(byte[] payload)
    {
        if (payload == null || payload.length == 0)
        {
            return Collections.emptyList();
        }
        if (payload.length >= HEADER_SIZE && payload[0] == VERSION)
        {
            return decodeCompressed(payload);
        }
        return decodeLegacy(payload);
    }

    private static byte[] rawPayload(List<Double> values)
    {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + values.size() * Double.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(values.size());
        for (Double value : values)
        {
            buffer.putDouble(value == null ? 0D : value);
        }
        return buffer.array();
    }

    private static List<Double> decodeCompressed(byte[] payload)
    {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        byte version = buffer.get();
        byte flags = buffer.get();
        buffer.getShort();
        int size = buffer.getInt();
        int rawLength = buffer.getInt();
        if (version != VERSION || size <= 0 || rawLength < Integer.BYTES)
        {
            return Collections.emptyList();
        }
        byte[] body = new byte[buffer.remaining()];
        buffer.get(body);
        byte[] raw = (flags & FLAG_LZ4) == FLAG_LZ4
                ? DECOMPRESSOR.decompress(body, rawLength)
                : body;
        return decodeLegacy(raw);
    }

    private static List<Double> decodeLegacy(byte[] payload)
    {
        if (payload.length < Integer.BYTES)
        {
            return Collections.emptyList();
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int size = buffer.getInt();
        if (size <= 0 || buffer.remaining() < (long) size * Double.BYTES)
        {
            return Collections.emptyList();
        }
        List<Double> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
        {
            values.add(buffer.getDouble());
        }
        return values;
    }
}
