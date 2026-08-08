package com.gameplatform.plugin.l4d2.rcon;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.gameplatform.plugin.l4d2.rcon.RconProtocol.*;

/**
 * RCON 连接缓存管理器。
 * <p>
 * 按 instanceId 缓存已认证连接，支持借用/归还、心跳保活、空闲超时回收、失效重建。
 * 同一实例串行访问（ReentrantLock），不同实例并行。
 */
@Slf4j
@Service
public class RconConnectionManager {

    private final RconConnectionResolver resolver;
    private final InstanceQueryService instanceQueryService;
    private final HostQueryService hostQueryService;
    private final L4D2Config config;

    private final ConcurrentHashMap<Long, CachedConnection> pool = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public RconConnectionManager(RconConnectionResolver resolver,
                                  InstanceQueryService instanceQueryService,
                                  HostQueryService hostQueryService,
                                  L4D2Config config) {
        this.resolver = resolver;
        this.instanceQueryService = instanceQueryService;
        this.hostQueryService = hostQueryService;
        this.config = config;

        L4D2Config.Rcon rconCfg = config.getRcon();
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rcon-connection-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::cleanIdleConnections,
                rconCfg.getCleanIntervalSeconds(),
                rconCfg.getCleanIntervalSeconds(),
                TimeUnit.SECONDS);
    }

    /**
     * 借用实例连接执行操作。
     */
    public <T> T withConnection(long instanceId, RconAction<T> action) {
        if (!config.getRcon().isPoolEnabled()) {
            return executeWithoutPool(instanceId, action);
        }
        CachedConnection cached = pool.computeIfAbsent(instanceId, this::createConnection);
        if (!cached.tryBorrow(config.getRcon().getBorrowTimeoutSeconds(), TimeUnit.SECONDS)) {
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "实例 " + instanceId + " 的 RCON 连接正忙");
        }
        CachedConnection active = cached;
        try {
            if (active.isBroken()) {
                active.close();
                active = createConnection(instanceId);
                pool.put(instanceId, active);
            }
            return action.execute(active.in, active.out);
        } catch (IOException e) {
            active.markBroken();
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 通信失败: " + e.getMessage(), e);
        } finally {
            active.release();
        }
    }

    /**
     * 缓存关闭时每次新建连接执行。
     */
    private <T> T executeWithoutPool(long instanceId, RconAction<T> action) {
        CachedConnection conn = createConnection(instanceId);
        try {
            return action.execute(conn.in, conn.out);
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 通信失败: " + e.getMessage(), e);
        } finally {
            conn.close();
        }
    }

    /**
     * 创建新连接（含认证）。
     */
    private CachedConnection createConnection(long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.RCON, "实例不存在: " + instanceId);
        }
        HostVO host = hostQueryService.getHostById(instance.getHostId());
        Optional<RconEndpoint> endpointOpt = resolver.resolve(instance, host);
        if (endpointOpt.isEmpty() || !endpointOpt.get().isValid()) {
            log.warn("RCON 端点不可达 instanceId={}, host={}, endpoint={}",
                    instanceId, host == null ? null : host.getIp(), endpointOpt);
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 端点不可达：端口未映射或配置缺失");
        }
        RconEndpoint ep = endpointOpt.get();
        log.info("RCON 创建连接 instanceId={}, host={}, port={}, passwordLength={}, deployType={}",
                instanceId, ep.host(), ep.port(),
                ep.password() == null ? 0 : ep.password().length(), instance.getDeployType());

        // 认证重试：服务器可能在连接建立后需要短暂时间初始化 RCON 处理器，
        // 首次 AUTH 可能被丢弃（服务器主动关闭连接）。
        // 关键发现：Source 引擎对空闲 TCP 连接有极短超时（实测约 20ms），
        // connect 后必须立即发送 AUTH 包，任何延迟（getInputStream/getOutputStream/buildPacket）
        // 都可能导致服务器关闭连接。因此在 connect 前预构建 AUTH 包。
        final int requestId = 1;
        int maxAttempts = 3;
        IOException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long tStart = System.nanoTime();
            try {
                // 预构建 AUTH 包（在 connect 之前，避免 connect 后构建延迟）
                byte[] authPacket = buildAuthPacket(requestId, ep.password());

                Socket socket = new Socket();
                socket.setSoTimeout(config.getRconTimeout());
                // 关键：禁用 Nagle 算法（TCP_NODELAY=true），确保 20 字节的 AUTH 包立即发送。
                // Source 引擎对空闲 TCP 连接有极短超时（约 20ms），Nagle 会缓冲小包等待 ACK，
                // 导致 AUTH 包延迟到达，服务器关闭连接。Python 的 sendall 不受此影响。
                socket.setTcpNoDelay(true);
                long tConnectStart = System.nanoTime();
                socket.connect(new java.net.InetSocketAddress(ep.host(), ep.port()), 5000);
                long tConnectEnd = System.nanoTime();

                // 关键：connect 后等待 5ms，确保 TCP 握手完全完成。
                // Python 的 connect 需要 16-21ms 返回（包含完整握手），而 Java 的 connect 0ms 返回。
                // 0ms 返回可能导致 write 时服务器端尚未准备好接收数据，导致连接被关闭。
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                // 关键路径：connect 后立即 write + flush，不做任何其他操作。
                // 服务器对空闲 TCP 连接有极短超时（约 20ms），任何延迟都会导致连接被关闭。
                OutputStream out = socket.getOutputStream();
                out.write(authPacket);
                out.flush();

                // 读取认证响应
                InputStream in = socket.getInputStream();
                readAuthResponse(in, requestId);

                long tAuthEnd = System.nanoTime();
                log.info("RCON 认证成功 attempt={}, instanceId={}, local={}, remote={}, connectMs={}, authMs={}, totalMs={}",
                        attempt, instanceId, socket.getLocalSocketAddress(), socket.getRemoteSocketAddress(),
                        (tConnectEnd - tConnectStart) / 1_000_000,
                        (tAuthEnd - tConnectEnd) / 1_000_000,
                        (tAuthEnd - tStart) / 1_000_000);
                return new CachedConnection(instanceId, socket);
            } catch (java.net.SocketTimeoutException e) {
                log.warn("RCON 连接超时 attempt={}, instanceId={}, host={}, port={}, err={}",
                        attempt, instanceId, ep.host(), ep.port(), e.toString());
                lastException = e;
            } catch (java.net.ConnectException e) {
                log.warn("RCON 连接被拒绝 attempt={}, instanceId={}, host={}, port={}, err={}",
                        attempt, instanceId, ep.host(), ep.port(), e.toString());
                lastException = e;
            } catch (IOException e) {
                log.warn("RCON 认证失败 attempt={}, instanceId={}, host={}, port={}, errClass={}, err={}",
                        attempt, instanceId, ep.host(), ep.port(), e.getClass().getSimpleName(), e.toString());
                lastException = e;
            }
            // 重试前等待 200ms
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 所有重试均失败
        String errMsg = lastException == null ? "未知错误" : lastException.getMessage();
        throw new L4D2PluginException(L4D2PluginException.RCON,
                "RCON 连接失败（重试 " + maxAttempts + " 次均失败）: " + errMsg, lastException);
    }

    /**
     * 后台清理空闲/失效/超龄连接。
     */
    private void cleanIdleConnections() {
        long now = System.currentTimeMillis();
        long idleTimeoutMs = config.getRcon().getIdleTimeoutSeconds() * 1000L;
        long maxAgeMs = config.getRcon().getMaxAgeSeconds() * 1000L;

        pool.entrySet().removeIf(entry -> {
            CachedConnection c = entry.getValue();
            if (c.isBroken()) {
                c.close();
                return true;
            }
            if (now - c.createdAt > maxAgeMs) {
                c.close();
                return true;
            }
            if (now - c.lastUsedAt > idleTimeoutMs) {
                if (c.tryBorrow(1, TimeUnit.SECONDS)) {
                    try {
                        // 心跳：发空命令验证存活
                        sendCommand(c.in, c.out, "");
                        c.lastUsedAt = now;
                    } catch (IOException e) {
                        c.markBroken();
                        c.close();
                        return true;
                    } finally {
                        c.release();
                    }
                    return false;
                }
                // 借不到（正忙）则保留
                return false;
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        cleaner.shutdownNow();
        pool.values().forEach(CachedConnection::close);
        pool.clear();
    }

    // ========== 内部类 ==========

    @FunctionalInterface
    public interface RconAction<T> {
        T execute(InputStream in, OutputStream out) throws IOException;
    }

    private static class CachedConnection {
        private final long instanceId;
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private volatile long lastUsedAt;
        private final long createdAt;
        private volatile boolean broken;
        private final ReentrantLock lock = new ReentrantLock();

        CachedConnection(long instanceId, Socket socket) throws IOException {
            this.instanceId = instanceId;
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.lastUsedAt = System.currentTimeMillis();
            this.createdAt = System.currentTimeMillis();
        }

        boolean tryBorrow(long timeout, TimeUnit unit) {
            try {
                return lock.tryLock(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void release() {
            lastUsedAt = System.currentTimeMillis();
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        void markBroken() {
            broken = true;
        }

        boolean isBroken() {
            return broken;
        }

        void close() {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                log.warn("关闭 RCON 连接失败 instanceId={}", instanceId, e);
            }
        }
    }
}
