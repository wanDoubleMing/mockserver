package org.mockserver.mockserver;

import com.google.common.util.concurrent.SettableFuture;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AttributeKey;
import org.mockserver.filters.LogFilter;
import org.mockserver.mock.MockServerMatcher;
import org.mockserver.stop.StopEventQueue;
import org.mockserver.stop.Stoppable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * An HTTP server that sends back the content of the received HTTP request
 * in a pretty plaintext form.
 */
public class MockServer implements Stoppable {

    public static final AttributeKey<LogFilter> LOG_FILTER = AttributeKey.valueOf("SERVER_LOG_FILTER");
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    // mockserver
    private final MockServerMatcher mockServerMatcher = new MockServerMatcher();
    private final LogFilter logFilter = new LogFilter();
    private final List<Future<Channel>> channelOpenedFutures = new ArrayList<Future<Channel>>();
    // netty
    private final EventLoopGroup bossGroup = new NioEventLoopGroup();
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final ServerBootstrap serverBootstrap;
    private StopEventQueue stopEventQueue = new StopEventQueue();

    /**
     * Start the instance using the port provided
     *
     * @param requestedPortBindings the http port to use
     */
    public MockServer(final Integer... requestedPortBindings) {
        if (requestedPortBindings == null || requestedPortBindings.length == 0) {
            throw new IllegalArgumentException("You must specify at least one port");
        }

        serverBootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, true)
                .childHandler(new MockServerInitializer(mockServerMatcher, MockServer.this))
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childAttr(LOG_FILTER, logFilter);

        bindToPorts(Arrays.asList(requestedPortBindings));
    }

    public List<Integer> bindToPorts(final List<Integer> requestedPortBindings) {
        List<Integer> actualPortBindings = new ArrayList<Integer>();
        for (final Integer port : requestedPortBindings) {
            try {
                final SettableFuture<Channel> channelOpened = SettableFuture.create();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        channelOpenedFutures.add(channelOpened);
                        try {

                            Channel channel =
                                    serverBootstrap
                                            .bind(port)
                                            .addListener(new ChannelFutureListener() {
                                                @Override
                                                public void operationComplete(ChannelFuture future) throws Exception {
                                                    if (future.isSuccess()) {
                                                        channelOpened.set(future.channel());
                                                    } else {
                                                        channelOpened.setException(future.cause());
                                                    }
                                                }
                                            })
                                            .channel();

                            logger.info("MockServer started on port: {}", ((InetSocketAddress) channelOpened.get().localAddress()).getPort());

                            channel.closeFuture().syncUninterruptibly();
                        } catch (Exception e) {
                            throw new RuntimeException("Exception while starting MockServer on port " + port, e.getCause());
                        } finally {
                            bossGroup.shutdownGracefully(0, 1, TimeUnit.MILLISECONDS);
                            workerGroup.shutdownGracefully(0, 1, TimeUnit.MILLISECONDS);
                        }
                    }
                }, "MockServer thread for port: " + port).start();

                actualPortBindings.add(((InetSocketAddress) channelOpened.get().localAddress()).getPort());
            } catch (Exception e) {
                throw new RuntimeException("Exception while starting MockServer on port " + port, e.getCause());
            }
        }
        return actualPortBindings;
    }

    public void stop() {
        try {
            for (Future<Channel> channelOpened : channelOpenedFutures) {
                channelOpened.get(2, TimeUnit.SECONDS).close();
            }
            bossGroup.shutdownGracefully(0, 1, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, 1, TimeUnit.MILLISECONDS);
            stopEventQueue.stop();
            // wait for socket to be released
            TimeUnit.MILLISECONDS.sleep(500);
        } catch (Exception ie) {
            logger.trace("Exception while stopping MockServer", ie);
        }
    }

    public MockServer withStopEventQueue(StopEventQueue stopEventQueue) {
        this.stopEventQueue = stopEventQueue;
        this.stopEventQueue.register(this);
        return this;
    }

    public boolean isRunning() {
        try {
            TimeUnit.MILLISECONDS.sleep(500);
        } catch (InterruptedException e) {
            logger.trace("Exception while waiting for the proxy to confirm running status", e);
        }
        return !bossGroup.isShuttingDown() && !workerGroup.isShuttingDown();
    }

    public List<Integer> getPorts() {
        List<Integer> ports = new ArrayList<Integer>();
        for (Future<Channel> channelOpened : channelOpenedFutures) {
            try {
                ports.add(((InetSocketAddress) channelOpened.get(2, TimeUnit.SECONDS).localAddress()).getPort());
            } catch (Exception e) {
                logger.trace("Exception while retrieving port from channel future, ignoring port for this channel", e);
            }
        }
        return ports;
    }

    public int getPort() {
        for (Future<Channel> channelOpened : channelOpenedFutures) {
            try {
                return ((InetSocketAddress) channelOpened.get(2, TimeUnit.SECONDS).localAddress()).getPort();
            } catch (Exception e) {
                logger.trace("Exception while retrieving port from channel future, ignoring port for this channel", e);
            }
        }
        return -1;
    }
}
