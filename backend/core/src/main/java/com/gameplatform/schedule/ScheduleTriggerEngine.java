package com.gameplatform.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.config.ScheduledTaskProperties;
import com.gameplatform.entity.ScheduledTask;
import com.gameplatform.entity.ScheduledTaskRun;
import com.gameplatform.enums.ScheduleRunStatus;
import com.gameplatform.mapper.ScheduledTaskRunLogMapper;
import com.gameplatform.mapper.ScheduledTaskRunMapper;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.schedule.ScheduledTaskHandler;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import com.gameplatform.task.TaskLogFlushExecutor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时触发执行引擎（ADR-0011）
 *
 * <p>职责：到点/手动触发 → 重叠检查（计划级跳过，D6）→ 落 RUNNING 记录 →
 * 异步执行 Handler → 终态落盘。执行语义：
 * <ul>
 *   <li>无自动重试：失败仅终态 FAILED，下一轮 cron 即天然重试（D6）</li>
 *   <li>超时：协作式（ctx.isTimeout）+ 30s grace 后强制中断（对齐任务中心 ADR-009 混合模式）</li>
 *   <li>取消：协作式（ctx.isCancelled）+ 30s grace 后强制中断</li>
 *   <li>Handler 未注册：直接 FAILED（创建时不校验注册表，D6）</li>
 * </ul>
 *
 * <p>线程模型：专用 run 执行池（默认 4 线程，与任务中心隔离，D7）+
 * 单线程超时守护（SimpleAsyncTaskExecutor 风格，每次新建守护任务）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class ScheduleTriggerEngine {

    /** 超时/取消 grace period：30s（对齐任务中心 ADR-009） */
    private static final long GRACE_PERIOD_MS = 30_000L;

    /** result 序列化上限：256KB（对齐任务中心 ADR-026） */
    private static final int RESULT_MAX_BYTES = 256 * 1024;

    /** error_message 截断长度 */
    private static final int ERROR_MAX_LENGTH = 4000;

    private final ScheduledTaskRunMapper runMapper;
    private final ScheduledTaskRunLogMapper runLogMapper;
    private final ScheduledTaskHandlerRegistry handlerRegistry;
    private final ExtensionIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final TaskLogFlushExecutor logFlushExecutor;

    /** run 执行线程池（专用，与任务中心隔离） */
    private final ExecutorService runExecutor;

    /** 超时/取消守护线程池 */
    private final ScheduledExecutorService timeoutWatchExecutor;

    /** 进行中 run 的上下文（runId -> ctx；trigger 时即注册，供取消用） */
    private final Map<String, ScheduleRunContext> activeContexts = new ConcurrentHashMap<>();

    /** 进行中 run 的执行 Future（runId -> future；供超时/取消强制中断用） */
    private final Map<String, Future<?>> activeFutures = new ConcurrentHashMap<>();

    public ScheduleTriggerEngine(ScheduledTaskRunMapper runMapper,
                                 ScheduledTaskRunLogMapper runLogMapper,
                                 ScheduledTaskHandlerRegistry handlerRegistry,
                                 ExtensionIdGenerator idGenerator,
                                 ObjectMapper objectMapper,
                                 TaskLogFlushExecutor logFlushExecutor,
                                 ScheduledTaskProperties properties) {
        this.runMapper = runMapper;
        this.runLogMapper = runLogMapper;
        this.handlerRegistry = handlerRegistry;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.logFlushExecutor = logFlushExecutor;
        int poolSize = Math.max(1, properties.getPoolSize());
        this.runExecutor = new ThreadPoolExecutor(poolSize, poolSize,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "schedule-run");
            t.setDaemon(true);
            return t;
        });
        this.timeoutWatchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "schedule-timeout-watch");
            t.setDaemon(true);
            return t;
        });
        log.info("[Schedule] 触发执行引擎已初始化（执行线程 {}）", poolSize);
    }

    /**
     * 触发一次计划执行（cron 到点或手动）
     *
     * <p>重叠检查为计划级语义（同计划上一轮 RUNNING 则 SKIPPED）；
     * 不同计划（即使引用同一 Handler）各自独立允许并发。
     *
     * @param schedule 计划实体（含 payload 模板快照）
     * @param manual   true 表示手动触发（trigger_type=MANUAL）
     * @return runId
     */
    public String trigger(ScheduledTask schedule, boolean manual) {
        String scheduleId = schedule.getId();

        // 1. 计划级重叠检查（ADR-0011 D6）
        if (runMapper.countRunningByScheduleId(scheduleId) > 0) {
            return insertSkippedRun(schedule, manual,
                    manual ? "上一轮仍在执行，手动触发被跳过" : "上一轮仍在执行，本次触发跳过");
        }

        // 2. 落 RUNNING 记录（含 payload 快照）
        String runId = idGenerator.nextId();
        ScheduledTaskRun run = new ScheduledTaskRun();
        run.setId(runId);
        run.setScheduleId(scheduleId);
        run.setScheduleName(schedule.getName());
        run.setTriggerType(manual ? "MANUAL" : "CRON");
        run.setStatus(ScheduleRunStatus.RUNNING.name());
        run.setPayload(schedule.getPayload());
        run.setProgress(0);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        // 3. 提前创建上下文并注册（保证 cancelRun 在排队期也能命中）
        ScheduleRunContext ctx = new ScheduleRunContext(runId, schedule.getHandlerKey(),
                schedule.getSource(), runMapper, runLogMapper, idGenerator, logFlushExecutor);
        activeContexts.put(runId, ctx);

        // 4. 异步执行
        Future<?> future = runExecutor.submit(() -> executeRun(schedule, runId, ctx));
        activeFutures.put(runId, future);

        log.info("[Schedule] 计划 [{}] 触发 {} 执行 runId={}（handler={}:{}, cron={})",
                schedule.getName(), manual ? "手动" : "定时", runId,
                schedule.getSource(), schedule.getHandlerKey(), schedule.getCron());
        return runId;
    }

    /**
     * 执行一次 run（run 执行线程内）
     */
    private void executeRun(ScheduledTask schedule, String runId, ScheduleRunContext ctx) {
        ScheduleRunStatus finalStatus;
        String errorMessage = null;
        String resultJson = null;
        try {
            // 1. 解析 Handler（触发时才校验注册表，ADR-0011 D6）
            ScheduledTaskHandler handler = handlerRegistry.get(schedule.getSource(), schedule.getHandlerKey());
            if (handler == null) {
                completeRun(runId, ScheduleRunStatus.FAILED,
                        "处理器未注册: " + schedule.getSource() + ":" + schedule.getHandlerKey()
                                + "（插件未加载或 Handler 未注册）", null);
                return;
            }

            // 2. 解析 payload 快照
            TaskPayload payload = parsePayload(schedule.getPayload());

            // 3. 超时守护（0 表示不超时）
            ScheduledFuture<?> timeoutFuture = null;
            long timeoutMs = handler.getDefaultTimeoutMs();
            if (timeoutMs > 0) {
                timeoutFuture = timeoutWatchExecutor.schedule(() -> onTimeout(runId, ctx), timeoutMs, TimeUnit.MILLISECONDS);
            }

            // 4. 执行
            TaskResult result;
            try {
                result = handler.execute(ctx, payload);
            } finally {
                if (timeoutFuture != null) {
                    timeoutFuture.cancel(false);
                }
            }

            // 5. 判定终态
            if (ctx.isCancelled()) {
                finalStatus = ScheduleRunStatus.CANCELLED;
                errorMessage = "执行已被取消";
            } else if (ctx.isTimeout()) {
                finalStatus = ScheduleRunStatus.FAILED;
                errorMessage = "任务执行超时（阈值 " + timeoutMs + "ms）";
            } else if (result == null || result.isSuccess()) {
                finalStatus = ScheduleRunStatus.SUCCEEDED;
                resultJson = serializeResult(result);
            } else {
                finalStatus = ScheduleRunStatus.FAILED;
                errorMessage = result.getMessage();
                resultJson = serializeResult(result);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (ctx.isTimeout()) {
                finalStatus = ScheduleRunStatus.FAILED;
                errorMessage = "任务执行超时被强制中断";
            } else {
                finalStatus = ScheduleRunStatus.CANCELLED;
                errorMessage = "执行被取消（中断）";
            }
        } catch (Throwable t) {
            log.error("[Schedule] runId={} 执行异常", runId, t);
            if (ctx.isCancelled()) {
                finalStatus = ScheduleRunStatus.CANCELLED;
                errorMessage = "执行被取消";
            } else {
                finalStatus = ScheduleRunStatus.FAILED;
                errorMessage = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            }
        } finally {
            activeFutures.remove(runId);
            activeContexts.remove(runId);
            try {
                ctx.close();
            } catch (Exception e) {
                log.warn("[Schedule] runId={} 上下文关闭异常: {}", runId, e.getMessage());
            }
        }

        completeRun(runId, finalStatus, errorMessage, resultJson);
    }

    /**
     * 超时回调：置 timeout 标志（协作式退出），grace period 后强制中断
     */
    private void onTimeout(String runId, ScheduleRunContext ctx) {
        ctx.markTimeout();
        timeoutWatchExecutor.schedule(() -> {
            Future<?> future = activeFutures.get(runId);
            if (future != null && !future.isDone()) {
                future.cancel(true);
                log.warn("[Schedule] runId={} 超时 grace period 后强制中断", runId);
            }
        }, GRACE_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消进行中的 run（协作式优先，30s grace 后强制中断）
     *
     * @param runId runId
     * @return true 表示已请求取消
     */
    public boolean cancelRun(String runId) {
        ScheduleRunContext ctx = activeContexts.get(runId);
        Future<?> future = activeFutures.get(runId);
        if (ctx == null && (future == null || future.isDone())) {
            return false;
        }
        if (ctx != null) {
            ctx.markCancelled();
        }
        if (future != null) {
            timeoutWatchExecutor.schedule(() -> {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }, GRACE_PERIOD_MS, TimeUnit.MILLISECONDS);
        }
        log.info("[Schedule] runId={} 已请求取消", runId);
        return true;
    }

    /**
     * 取消计划的所有进行中 run（删除计划/插件卸载时调用）
     *
     * @param scheduleId 计划ID
     * @return 请求取消的 run 数量
     */
    public int cancelRunsBySchedule(String scheduleId) {
        int count = 0;
        for (ScheduledTaskRun run : runMapper.selectRunningByScheduleId(scheduleId)) {
            if (cancelRun(run.getId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 启动崩溃恢复：将重启前遗留的 RUNNING 记录置 FAILED（ADR-0011 D6 停机不补跑）
     *
     * @return 恢复的记录数
     */
    public int recoverStaleRuns() {
        // 全表 RUNNING 记录：应用刚启动，所有 RUNNING 均为遗留（内存上下文已丢失）
        var running = runMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScheduledTaskRun>()
                .eq(ScheduledTaskRun::getStatus, ScheduleRunStatus.RUNNING.name()));
        for (ScheduledTaskRun run : running) {
            completeRun(run.getId(), ScheduleRunStatus.FAILED, "平台重启导致执行中断（停机期间触发不补跑）", null);
        }
        if (!running.isEmpty()) {
            log.warn("[Schedule] 启动崩溃恢复：{} 条 RUNNING 记录置 FAILED", running.size());
        }
        return running.size();
    }

    /**
     * 判断 run 是否处于进行中（引擎内存视角）
     */
    public boolean isRunActive(String runId) {
        return activeContexts.containsKey(runId);
    }

    // ==================== 私有方法 ====================

    private String insertSkippedRun(ScheduledTask schedule, boolean manual, String reason) {
        String runId = idGenerator.nextId();
        ScheduledTaskRun run = new ScheduledTaskRun();
        run.setId(runId);
        run.setScheduleId(schedule.getId());
        run.setScheduleName(schedule.getName());
        run.setTriggerType(manual ? "MANUAL" : "CRON");
        run.setStatus(ScheduleRunStatus.SKIPPED.name());
        run.setPayload(schedule.getPayload());
        run.setErrorMessage(reason);
        run.setCompletedAt(LocalDateTime.now());
        run.setDurationMs(0L);
        runMapper.insert(run);
        log.info("[Schedule] 计划 [{}] 触发跳过（{}）runId={}", schedule.getName(), reason, runId);
        return runId;
    }

    private void completeRun(String runId, ScheduleRunStatus status, String errorMessage, String resultJson) {
        try {
            ScheduledTaskRun update = new ScheduledTaskRun();
            update.setId(runId);
            update.setStatus(status.name());
            update.setCompletedAt(LocalDateTime.now());
            if (errorMessage != null) {
                update.setErrorMessage(errorMessage.length() > ERROR_MAX_LENGTH
                        ? errorMessage.substring(0, ERROR_MAX_LENGTH) : errorMessage);
            }
            if (resultJson != null) {
                update.setResult(resultJson);
            }
            // 耗时从 create_time 起算（含排队时间）
            ScheduledTaskRun current = runMapper.selectById(runId);
            if (current != null && current.getCreateTime() != null) {
                long durationMs = Duration.between(current.getCreateTime(), LocalDateTime.now()).toMillis();
                update.setDurationMs(durationMs);
            }
            runMapper.updateById(update);
            log.info("[Schedule] runId={} 终态: {}", runId, status);
        } catch (Exception e) {
            log.error("[Schedule] runId={} 终态落盘失败: {}", runId, e.getMessage(), e);
        }
    }

    private TaskPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return new TaskPayload(Map.of());
        }
        try {
            Map<String, Object> data = objectMapper.readValue(payloadJson, new TypeReference<>() {
            });
            return new TaskPayload(data != null ? data : Map.of());
        } catch (Exception e) {
            log.warn("[Schedule] payload 解析失败，按空 payload 执行: {}", e.getMessage());
            return new TaskPayload(Map.of());
        }
    }

    private String serializeResult(TaskResult result) {
        if (result == null) {
            return null;
        }
        try {
            Map<String, Object> out = new HashMap<>();
            out.put("success", result.isSuccess());
            out.put("message", result.getMessage());
            out.put("data", result.getData());
            String json = objectMapper.writeValueAsString(out);
            return json.length() > RESULT_MAX_BYTES ? null : json;
        } catch (Exception e) {
            log.warn("[Schedule] result 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Schedule] 关闭触发执行引擎");
        runExecutor.shutdown();
        timeoutWatchExecutor.shutdown();
        try {
            if (!runExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                runExecutor.shutdownNow();
            }
            if (!timeoutWatchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutWatchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            runExecutor.shutdownNow();
            timeoutWatchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
