package com.ruoyi.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class VibrationSimulatorApplication {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8088;
    private static final int HEARTBEAT_SECONDS = 5;
    private static final int SAMPLE_SECONDS = 1;

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        new VibrationSimulatorApplication().start(host, port);
    }

    public void start(String host, int port) {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LengthFieldPrepender(4));
                        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4));
                        ch.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
                        ch.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
                        ch.pipeline().addLast(new SimulatorClientHandler(host, port, bootstrap));
                    }
                });

            connect(bootstrap, host, port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> group.shutdownGracefully()));
            group.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            group.shutdownGracefully();
        }
    }

    private void connect(Bootstrap bootstrap, String host, int port) {
        bootstrap.connect(host, port).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                System.out.println("Connected to " + host + ":" + port);
            } else {
                scheduleReconnect(future.channel(), bootstrap, host, port);
            }
        });
    }

    private void scheduleReconnect(Channel channel, Bootstrap bootstrap, String host, int port) {
        channel.eventLoop().schedule(() -> connect(bootstrap, host, port), 3, TimeUnit.SECONDS);
    }

    private static final class SimulatorClientHandler extends SimpleChannelInboundHandler<String> {
        private final String host;
        private final int port;
        private final Bootstrap bootstrap;
        private final Random random = new Random();
        private final AtomicInteger seq = new AtomicInteger(1);
        private double phase = 0d;

        private SimulatorClientHandler(String host, int port, Bootstrap bootstrap) {
            this.host = host;
            this.port = port;
            this.bootstrap = bootstrap;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            scheduleHeartbeat(ctx);
            scheduleSample(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            System.out.println("Disconnected, reconnecting...");
            ctx.channel().eventLoop().schedule(() -> bootstrap.connect(host, port), 3, TimeUnit.SECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            System.out.println("SERVER> " + msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Client error: " + cause.getMessage());
            ctx.close();
        }

        private void scheduleHeartbeat(ChannelHandlerContext ctx) {
            ctx.executor().scheduleAtFixedRate(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(buildMessage("HEARTBEAT", 0, 0, 0));
                }
            }, 0, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }

        private void scheduleSample(ChannelHandlerContext ctx) {
            ctx.executor().scheduleAtFixedRate(() -> {
                if (ctx.channel().isActive()) {
                    double[] xyz = generateVibration();
                    ctx.writeAndFlush(buildMessage("VIBRATION", xyz[0], xyz[1], xyz[2]));
                }
            }, 0, SAMPLE_SECONDS, TimeUnit.SECONDS);
        }

        private double[] generateVibration() {
            phase += 0.35;
            double baseX = Math.sin(phase) * 2.4;
            double baseY = Math.sin(phase + Math.PI / 3) * 1.8;
            double baseZ = Math.sin(phase + Math.PI / 1.7) * 3.1;
            return new double[] {
                round3(baseX + noise()),
                round3(baseY + noise()),
                round3(baseZ + noise())
            };
        }

        private double noise() {
            return (random.nextDouble() - 0.5) * 0.24;
        }

        private String buildMessage(String type, double x, double y, double z) {
            String header = Arrays.asList("__header__", "__version__", "__globals__", "DE_time", "sr", "rpm", "load", "fault_type", "fault_size").toString();
            String version = "1.0";
            String globals = "[]";
            double deTime = x;
            double sr = 25600.0;
            double rpm = 1500.0;
            double load = 0.75;
            String faultType = type;
            double faultSize = round3(Math.abs(y) + Math.abs(z));
            return String.join(",",
                quote(header),
                quote(version),
                quote(globals),
                formatDouble(deTime),
                formatDouble(sr),
                formatDouble(rpm),
                formatDouble(load),
                quote(faultType),
                formatDouble(faultSize)
            );
        }

        private String quote(String value) {
            return "'" + value.replace("'", "\\'") + "'";
        }

        private String formatDouble(double value) {
            return String.format(Locale.US, "%.3f", value);
        }

        private double round3(double value) {
            return Math.round(value * 1000.0) / 1000.0;
        }
    }
}
