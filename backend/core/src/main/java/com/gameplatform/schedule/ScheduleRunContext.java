package com.gameplatform.schedule;

import com.gameplatform.entity.ScheduledTaskRun;
import com.gameplatform.entity.ScheduledTaskRunLog;
import com.gameplatform.mapper.ScheduledTaskRunLogMapper;
import com.gameplatform.mapper.ScheduledTaskRunMapper;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.task.TaskLogFlushExecutor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时触发执行上下文（ADR-0011 D4）
 *
 * <p>run 版 {@link TaskContext} 实现——接口复用 plugin 模块的 TaskContext，
 * 落点切换到 scheduled_task_run / scheduled_task_run_log 表；
 * {@code getTaskId()} 返回 runId。
 *
 * <p>线程安全与节流策略对齐 {@code TaskContextImpl}：
 * volatile 标志位、1s 进度节流、ConcurrentLinkedQueue 日志缓冲 1s 批量刷盘。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
public class ScheduleRunContext implements TaskContext {

    /** 日志刷盘周期（毫秒） */
    private static final long LOG_FLUSH_PERIOD_MS = 1000L;

    /** 进度节流阈值（毫秒） */
    private static final long PROGRESS_THROTTLE_MS = 1000L;

    /** 日志保留上限 */
    private static final int LOG_KEEP_LIMIT = 500;

    private final String runId;
    private final String handlerKey;
    private final String source;
    private final ScheduledTaskRunMapper runMapper;
    private final ScheduledTaskRunLogMapper runLogMapper;
    private final ExtensionIdGenerator idGenerator;
    private final TaskLogFlushExecutor flushExecutor;

    /** 取消标志位（volatile，由 cancel 调用方写入，Handler 在循环中读取） */
    private volatile boolean cancelled = false;

    /** 超时标志位（volatile，由超时守护线程写入，Handler 在循环中读取） */
    private volatile boolean timeout = false;

    /** 进度节流字段 */
    private volatile int lastReportedPercent = -1;
    private volatile long lastReportedTime = 0L;
    private volatile int pendingProgress = 0;
    private volatile String pendingMessage = null;

    /** 日志缓冲队列 */
    private final ConcurrentLinkedQueue<ScheduledTaskRunLog> logBuffer = new ConcurrentLinkedQueue<>();

    /** 刷盘定时任务 Future（用于关闭） */
    private final ScheduledFuture<?> flushFuture;

    /** 上下文是否已关闭（防止重复 close） */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ScheduleRunContext(String runId, String handlerKey, String source,
                              ScheduledTaskRunMapper runMapper,
                              ScheduledTaskRunLogMapper runLogMapper,
                              ExtensionIdGenerator idGenerator,
                              TaskLogFlushExecutor flushExecutor) {
        this.runId = runId;
        this.handlerKey = handlerKey;
        this.source = source;
        this.runMapper = runMapper;
        this.runLogMapper = runLogMapper;
        this.idGenerator = idGenerator;
        this.flushExecutor = flushExecutor;
        this.lastReportedTime = System.currentTimeMillis();
        this.flushFuture = flushExecutor.scheduleAtFixedRate(this::flushLogs, LOG_FLUSH_PERIOD_MS);
    }

    @Override
    public String getTaskId() {
        return runId;
    }

    @Override
    public String getTaskType() {
        return handlerKey;
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public String getScopeKey() {
        // 定时计划无作用域概念（ADR-0011 D9：payload 自由携带 instanceId）
        return null;
    }

    @Override
    public void reportProgress(int percent, String message) {
        if (closed.get()) {
            return;
        }
        int clamped = Math.max(0, Math.min(100, percent));

        if (clamped == lastReportedPercent && clamped != 100) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean force = clamped == 100 || (now - lastReportedTime) >= PROGRESS_THROTTLE_MS;
        if (force) {
            doFlushProgress(clamped, message, now);
        } else {
            pendingProgress = clamped;
            pendingMessage = message;
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isTimeout() {
        return timeout;
    }

    @Override
    public void log(String message) {
        log("INFO", message);
    }

    @Override
    public void log(String level, String message) {
        if (message == null) {
            return;
        }
        ScheduledTaskRunLog runLog = new ScheduledTaskRunLog();
        runLog.setId(idGenerator.nextId());
        runLog.setRunId(runId);
        runLog.setLevel(level != null ? level : "INFO");
        runLog.setMessage(message);
        runLog.setCreateTime(LocalDateTime.now());
        logBuffer.offer(runLog);
    }

    /**
     * 标记取消（由引擎 cancelRun 调用）
     */
    public void markCancelled() {
        this.cancelled = true;
    }

    /**
     * 标记超时（由超时守护线程调用）
     */
    public void markTimeout() {
        this.timeout = true;
    }

    /**
     * 关闭上下文：强制刷盘剩余日志 + 关闭刷盘定时器 + 触发 500 条上限清理
     *
     * <p>在引擎执行流程的 finally 块调用，幂等。
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (flushFuture != null) {
            flushFuture.cancel(false);
        }
        flushLogs();
        try {
            runLogMapper.deleteOldLogs(runId, LOG_KEEP_LIMIT);
        } catch (Exception e) {
            log.warn("[Schedule] 触发记录 {} 日志上限清理失败: {}", runId, e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    private void doFlushProgress(int percent, String message, long now) {
        try {
            ScheduledTaskRun update = new ScheduledTaskRun();
            update.setId(runId);
            update.setProgress(percent);
            update.setProgressMessage(message);
            runMapper.updateById(update);
            lastReportedPercent = percent;
            lastReportedTime = now;
            pendingProgress = percent;
            pendingMessage = message;
        } catch (Exception e) {
            log.warn("[Schedule] 触发记录 {} 进度刷盘失败: {}", runId, e.getMessage());
        }
    }

    private void flushLogs() {
        if (logBuffer.isEmpty()) {
            return;
        }
        List<ScheduledTaskRunLog> batch = new ArrayList<>();
        ScheduledTaskRunLog item;
        while ((item = logBuffer.poll()) != null) {
            batch.add(item);
            if (batch.size() >= 100) {
                flushBatch(batch);
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            flushBatch(batch);
        }
    }

    private void flushBatch(List<ScheduledTaskRunLog> batch) {
        try {
            for (ScheduledTaskRunLog runLog : batch) {
                runLogMapper.insert(runLog);
            }
        } catch (Exception e) {
            log.warn("[Schedule] 触发记录 {} 日志刷盘失败（{} 条）: {}", runId, batch.size(), e.getMessage());
        }
    }
}
