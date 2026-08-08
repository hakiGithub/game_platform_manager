package com.gameplatform.task;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 共享任务日志刷盘定时器（ADR-033）
 *
 * <p>单例 {@link ScheduledExecutorService}（core=1）由所有 {@link TaskContextImpl} 共享。
 * 任务结束时只取消自己的 {@link ScheduledFuture}，不关闭整个 executor。
 *
 * <p><b>设计原因</b>：避免每个任务启动独立的 executor 导致线程膨胀；
 * 单线程足够处理日志批量刷盘（任务并发度低，1s 周期聚合）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class TaskLogFlushExecutor {

    /** 共享刷盘线程池（守护线程，单线程足够） */
    private final ScheduledExecutorService scheduler;

    public TaskLogFlushExecutor() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-log-flusher");
            t.setDaemon(true);
            return t;
        });
        log.info("[TaskCenter] 任务日志刷盘定时器已初始化");
    }

    /**
     * 调度周期性任务
     *
     * @param command 任务
     * @param periodMs 周期（毫秒）
     * @return ScheduledFuture，可用于取消
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long periodMs) {
        return scheduler.scheduleAtFixedRate(command, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        log.info("[TaskCenter] 关闭任务日志刷盘定时器");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
