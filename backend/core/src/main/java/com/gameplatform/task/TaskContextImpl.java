package com.gameplatform.task;

import com.gameplatform.entity.TaskLog;
import com.gameplatform.mapper.TaskLogMapper;
import com.gameplatform.mapper.TaskRecordMapper;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.task.TaskContext;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务执行上下文实现（ADR-019 线程安全策略）
 *
 * <p>线程安全组合：
 * <ul>
 *   <li>{@code volatile} 标志位：cancelled / timeout，单写多读</li>
 *   <li>{@code volatile} 进度节流字段：lastReportedPercent / lastReportedTime</li>
 *   <li>{@link ConcurrentLinkedQueue} 日志缓冲队列，1s 批量刷盘</li>
 *   <li>独立的刷盘定时器由 {@link TaskLogFlushExecutor} 共享</li>
 * </ul>
 *
 * <p><b>进度节流（ADR-014）</b>：
 * <ul>
 *   <li>相同 percent 不写 DB</li>
 *   <li>不同 percent 距上次写入 < 1s，仅更新内存 pendingProgress / pendingMessage</li>
 *   <li>距上次写入 ≥ 1s，或 percent=100，或任务终态时，强制刷盘</li>
 * </ul>
 *
 * <p><b>日志缓冲（ADR-023）</b>：logBuffer 在内存中，应用崩溃时未刷盘的日志丢失。
 * 但任务终态时必须强制刷盘；execute 方法的 finally 块会捕获 Handler 末尾的 log 调用。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
public class TaskContextImpl implements TaskContext {

    /** 日志刷盘周期（毫秒） */
    private static final long LOG_FLUSH_PERIOD_MS = 1000L;

    /** 进度节流阈值（毫秒） */
    private static final long PROGRESS_THROTTLE_MS = 1000L;

    /** 日志保留上限（ADR-010） */
    private static final int LOG_KEEP_LIMIT = 500;

    private final String taskId;
    private final String taskType;
    private final String source;
    private final String scopeKey;
    private final TaskRecordMapper taskRecordMapper;
    private final TaskLogMapper taskLogMapper;
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
    private final ConcurrentLinkedQueue<TaskLog> logBuffer = new ConcurrentLinkedQueue<>();

    /** 刷盘定时任务 Future（用于关闭） */
    private final ScheduledFuture<?> flushFuture;

    /** 上下文是否已关闭（防止重复 close） */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TaskContextImpl(String taskId, String taskType, String source, String scopeKey,
                           TaskRecordMapper taskRecordMapper, TaskLogMapper taskLogMapper,
                           ExtensionIdGenerator idGenerator, TaskLogFlushExecutor flushExecutor) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.source = source;
        this.scopeKey = scopeKey;
        this.taskRecordMapper = taskRecordMapper;
        this.taskLogMapper = taskLogMapper;
        this.idGenerator = idGenerator;
        this.flushExecutor = flushExecutor;
        this.lastReportedTime = System.currentTimeMillis();
        this.flushFuture = flushExecutor.scheduleAtFixedRate(this::flushLogs, LOG_FLUSH_PERIOD_MS);
    }

    @Override
    public String getTaskId() {
        return taskId;
    }

    @Override
    public String getTaskType() {
        return taskType;
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public String getScopeKey() {
        return scopeKey;
    }

    @Override
    public void reportProgress(int percent, String message) {
        if (closed.get()) {
            return;
        }
        // 范围校验
        int clamped = Math.max(0, Math.min(100, percent));

        // 相同 percent 忽略
        if (clamped == lastReportedPercent && clamped != 100) {
            return;
        }

        long now = System.currentTimeMillis();
        // 强制刷盘场景：percent=100，或距上次写入 ≥ 1s
        boolean force = clamped == 100 || (now - lastReportedTime) >= PROGRESS_THROTTLE_MS;
        if (force) {
            doFlushProgress(clamped, message, now);
        } else {
            // 仅更新内存
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
        log(TaskLog.LEVEL_INFO, message);
    }

    @Override
    public void log(String level, String message) {
        if (message == null) {
            return;
        }
        TaskLog taskLog = new TaskLog();
        taskLog.setId(idGenerator.nextId());
        taskLog.setTaskId(taskId);
        taskLog.setLevel(level != null ? level : TaskLog.LEVEL_INFO);
        taskLog.setMessage(message);
        taskLog.setCreateTime(LocalDateTime.now());
        logBuffer.offer(taskLog);
    }

    /**
     * 标记取消（由 TaskServiceImpl.cancel 调用）
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
     * 刷新挂起的进度（在 taskExecutor 取出新任务前调用，确保最新进度不丢失）
     */
    public void flushPendingProgress() {
        if (pendingProgress > lastReportedPercent) {
            doFlushProgress(pendingProgress, pendingMessage, System.currentTimeMillis());
        }
    }

    /**
     * 关闭上下文：强制刷盘剩余日志 + 关闭刷盘定时器 + 触发 500 条上限清理
     *
     * <p>在 TaskServiceImpl.executeAsync 的 finally 块调用，幂等。
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 1. 取消定时刷盘任务
        if (flushFuture != null) {
            flushFuture.cancel(false);
        }
        // 2. 强制刷盘剩余日志
        flushLogs();
        // 3. 异步触发 500 条上限清理（在调用线程执行，避免再开线程）
        try {
            taskLogMapper.deleteOldLogs(taskId, LOG_KEEP_LIMIT);
        } catch (Exception e) {
            log.warn("[TaskCenter] 任务 {} 日志上限清理失败: {}", taskId, e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    private void doFlushProgress(int percent, String message, long now) {
        try {
            taskRecordMapper.updateProgress(taskId, percent, message, LocalDateTime.now());
            lastReportedPercent = percent;
            lastReportedTime = now;
            pendingProgress = percent;
            pendingMessage = message;
        } catch (Exception e) {
            log.warn("[TaskCenter] 任务 {} 进度刷盘失败: {}", taskId, e.getMessage());
        }
    }

    /**
     * 批量刷盘日志缓冲队列
     */
    private void flushLogs() {
        if (logBuffer.isEmpty()) {
            return;
        }
        List<TaskLog> batch = new ArrayList<>();
        TaskLog item;
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

    private void flushBatch(List<TaskLog> batch) {
        try {
            // 逐条 insert（MyBatis-Plus BaseMapper 暂未提供批量 insert 方法，且任务日志并发度低）
            for (TaskLog taskLog : batch) {
                taskLogMapper.insert(taskLog);
            }
        } catch (Exception e) {
            log.warn("[TaskCenter] 任务 {} 日志刷盘失败（{} 条）: {}", taskId, batch.size(), e.getMessage());
        }
    }
}
