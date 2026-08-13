package com.gameplatform.websocket;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 连接生命周期深模块（架构评审 2026-08-13 候选 4）
 *
 * <p>统一承载 6 个 WebSocket handler 共享的能力：
 * <ul>
 *   <li>有界共享线程池（替代各处 newCachedThreadPool 的无界线程池）</li>
 *   <li>连接注册表：以 WebSocket sessionId 为键注册/注销可关闭资源，
 *       关闭语义（通道/会话/客户端释放）由资源自身的 close() 承担</li>
 *   <li>应用关闭时统一清理所有存活连接</li>
 * </ul>
 */
@Slf4j
@Component
public class ConnectionLifecycle {

    /** 有界 IO 线程池：核心 4 / 最大 16，队列有界，溢出由调用线程执行（背压而非无界增长） */
    private final ExecutorService executor;

    /** WebSocket sessionId -> 可关闭连接资源 */
    private final ConcurrentHashMap<String, AutoCloseable> registry = new ConcurrentHashMap<>();

    public ConnectionLifecycle() {
        this.executor = new ThreadPoolExecutor(
                4, 16, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> {
                    Thread t = new Thread(r, "ws-io");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 共享的有界 IO 线程池（阻塞式 SSH/Docker IO 任务在此执行） */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * 注册 WebSocket 会话对应的连接资源；同名 sessionId 会替换旧资源（先关闭旧资源）。
     */
    public void register(String wsSessionId, AutoCloseable resource) {
        AutoCloseable old = registry.put(wsSessionId, resource);
        if (old != null && old != resource) {
            closeQuietly(old);
        }
    }

    /**
     * 注销并关闭指定会话的连接资源（幂等）。
     */
    public void unregister(String wsSessionId) {
        AutoCloseable resource = registry.remove(wsSessionId);
        if (resource != null) {
            closeQuietly(resource);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("WebSocket 连接生命周期关闭：剩余 {} 个连接", registry.size());
        registry.values().forEach(this::closeQuietly);
        registry.clear();
        executor.shutdownNow();
    }

    private void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            log.warn("关闭 WebSocket 连接资源失败: {}", e.getMessage());
        }
    }
}
