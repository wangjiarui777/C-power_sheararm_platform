package com.ruoyi.sensor.service.impl;

import java.util.Date;

import com.ruoyi.sensor.domain.dto.ChannelFrameDTO;
import com.ruoyi.sensor.service.ChannelFrameIngestService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.springframework.stereotype.Component;

@Component
public class SensorTcpChannelHandler extends SimpleChannelInboundHandler<ByteBuf>
{
    private final ChannelFrameIngestService ingestService;

    public SensorTcpChannelHandler(ChannelFrameIngestService ingestService)
    {
        this.ingestService = ingestService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg)
    {
        byte[] payload = new byte[msg.readableBytes()];
        msg.readBytes(payload);

        ChannelFrameDTO dto = new ChannelFrameDTO();
        dto.setDeviceCode(ctx.channel().id().asShortText());
        dto.setBatchId(System.currentTimeMillis());
        dto.setSampleRate(1000D);
        dto.setCollectTime(new Date());
        dto.setPayload(payload);

        ingestService.ingest(dto);
    }
}
