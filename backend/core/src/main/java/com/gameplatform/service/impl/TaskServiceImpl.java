package com.gameplatform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.entity.TaskRecord;
import com.gameplatform.enums.TaskStatus;
import com.gameplatform.mapper.TaskLogMapper;
import com.gameplatform.mapper.TaskRecordMapper;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.task.*;
import com.gameplatform.task.TaskContextHolder;
import com.gameplatform.task.TaskContextImpl;
import com.gameplatform.task.TaskHandlerRegistry;
import com.gameplatform.task.TaskLogFlushExecutor;
import com.gameplatform.task.TaskMutexManager;
import com.gameplatform.task.exception.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务服务实现（{@link TaskService}）
 *
 * <p>提供任务提交、查询、取消本来源任务的能力（ADR-025）。
 * 通过 {@code @Lazy} 自注入实现 {@code @Async} 自调用。
 *
 * <p><b>核心流程</b>：
 * <ol>
 *   <li>{@link #submit}：同步提交流程（查找 Handler → 校验 → 互斥 → 持久化 → 异步触发）</li>
 *   <li>{@link #executeAsync}：异步执行流程（乐观锁 → 钩子 → 超时监控 → Future.cancel 兜底）</li>
 * </ol>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    /** payload 序列化上限：64KB */
    private static final int PAYLOAD_MAX_BYTES = 64 * 1024;

    /** result 序列化上限：256KB */
    private static final int RESULT_MAX_BYTES = 256 * 1024;

    /** 超时 grace period：30s（ADR-009） */
    private static final long TIMEOUT_GRACE_PERIOD_MS = 30_000L;

    private final TaskRecordMapper taskRecordMapper;
    private final TaskLogMapper taskLogMapper;
    private final TaskHandlerRegistry handlerRegistry;
    private final TaskMutexManager taskMutexManager;
    private final TaskContextHolder taskContextHolder;
    private final TaskLogFlushExecutor logFlushExecutor;
    private final ExtensionIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    /**
     * 取消请求标志（taskId -> true）
     *
     * <p>用于解决"cancel 在 executeAsync 创建 ctx 之前到达"的竞态（ADR-020 之外的边角场景）。
     * executeAsync 创建 ctx 后检查此 map，若存在则立即标记 cancelled。
     */
    private final Map<String, AtomicBoolean> cancelRequests = new ConcurrentHashMap<>();

    /**
     * 超时守护线程池（ADR-021：SimpleAsyncTaskExecutor，每次新建，执行完销毁）
     */
    private final ExecutorService timeoutWatchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "task-timeout-watch");
        t.setDaemon(true);
        return t;
    });

    /**
     * 自注入代理（用于触发 @Async 方法）
     *
     * <p>由 Spring 在创建 Bean 后通过 setter 注入，避免构造器循环依赖。
     */
    private TaskServiceImpl self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@Lazy TaskServiceImpl self) {
        this.self = self;
    }

    /**
     * 应用关闭时关闭超时守护线程池
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("[TaskCenter] 关闭超时守护线程池");
        timeoutWatchExecutor.shutdownNow();
    }

    // ==================== TaskService 接口实现 ====================

    @Override
    public String submit(TaskSubmitRequest request) {
        // 参数基础校验
        validateSubmitRequest(request);
        String source = request.getSource().toUpperCase();
        String taskType = request.getTaskType();

        // 1. 查找 Handler
        TaskHandler handler = handlerRegistry.get(source, taskType);
        if (handler == null) {
            throw new TaskTypeNotFoundException(source, taskType);
        }

        // 2. 校验 payload 大小（ADR-026）
        Map<String, Object> payloadMap = request.getPayload() != null
                ? request.getPayload() : Collections.emptyMap();
        String payloadJson = serializePayload(payloadMap);

        // 3. 调用 Handler.onSubmit 钩子（同步，在调用线程）
        TaskPayload payload = new TaskPayload(payloadMap);
        TaskSubmitContext submitCtx = new TaskSubmitContext(
                taskType, source, request.getScopeType(),
                request.getScopeKey(), request.getScopeName(),
                request.getSubmitter(), payload);
        try {
            handler.onSubmit(submitCtx);
        } catch (RuntimeException e) {
            log.warn("[TaskCenter] onSubmit 钩子抛异常，阻止提交: source={}, type={}, err={}",
                    source, taskType, e.getMessage());
            throw e;
        }

        // 4. 计算互斥键（ADR-011）
        String mutexKey = computeMutexKey(handler, payload, source, taskType, request.getScopeKey());

        // 5. 内存互斥检查（ADR-018）
        // 先生成 taskId，便于互斥键占用
        String taskId = idGenerator.nextId();
        if (mutexKey != null) {
            if (!taskMutexManager.putIfAbsent(mutexKey, taskId)) {
                throw new TaskAlreadyRunningException(mutexKey);
            }
        }

        // 6. 持久化 PENDING 记录
        TaskRecord record = buildTaskRecord(taskId, request, source, payloadJson, handler);
        try {
            taskRecordMapper.insert(record);
            handlerRegistry.indexTaskSource(taskId, source);
            log.info("[TaskCenter] 任务已提交: id={}, source={}, type={}, scope={}",
                    taskId, source, taskType, request.getScopeKey());
        } catch (Exception e) {
            // 持久化失败，释放互斥键
            if (mutexKey != null) {
                taskMutexManager.remove(mutexKey, taskId);
            }
            log.error("[TaskCenter] 任务持久化失败: id={}, err={}", taskId, e.getMessage());
            throw e instanceof TaskException te ? te : new TaskException("任务持久化失败: " + e.getMessage());
        }

        // 7. 触发异步执行（@Async 自调用必须通过代理）
        try {
            self.executeAsync(taskId);
        } catch (Exception e) {
            // @Async 调度失败（如线程池拒绝），交由 PENDING 超时检查兜底
            log.error("[TaskCenter] 异步调度失败，等待 PENDING 超时兜底: id={}, err={}",
                    taskId, e.getMessage());
        }

        return taskId;
    }

    @Override
    public TaskVO getTask(String taskId) {
        TaskRecord record = taskRecordMapper.selectById(taskId);
        if (record == null) {
            throw new TaskNotFoundException(taskId);
        }
        return convertToVO(record);
    }

    @Override
    public PageResult<TaskVO> listTasks(TaskQuery query) {
        Page<TaskRecord> page = new Page<>(
                query.getPage() != null ? query.getPage() : 1,
                query.getSize() != null ? query.getSize() : 20);
        LambdaQueryWrapper<TaskRecord> wrapper = buildQueryWrapper(query);
        IPage<TaskRecord> result = taskRecordMapper.selectPage(page, wrapper);

        List<TaskVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .toList();
        return new PageResult<>(voList, result.getTotal(),
                query.getPage() != null ? query.getPage() : 1,
                query.getSize() != null ? query.getSize() : 20);
    }

    @Override
    public List<TaskLog> getTaskLogs(String taskId) {
        List<com.gameplatform.entity.TaskLog> logs = taskLogMapper.selectByTaskId(taskId, 500);
        return logs.stream().map(this::convertLogToVO).toList();
    }

    @Override
    public boolean cancelMyOwn(String taskId) {
        TaskRecord record = taskRecordMapper.selectById(taskId);
        if (record == null) {
            throw new TaskNotFoundException(taskId);
        }
        // 校验调用方 source（插件子容器注入时由 PluginContextHolder 推断）
        // 第一版简化：cancelMyOwn 委托给统一 cancel 逻辑，不强制 source 检查
        // 因为插件无法获取其他 source 的 taskId，安全性由调用方保证
        return doCancel(taskId, record);
    }

    // ==================== 异步执行（@Async） ====================

    /**
     * 异步执行任务（{@code @Async("taskExecutor")} 复用主线程池，ADR-003/ADR-021）
     *
     * <p>包含：
     * <ul>
     *   <li>乐观锁 PENDING → RUNNING（ADR-020）</li>
     *   <li>创建 TaskContextImpl（含进度节流、日志缓冲、超时标志）</li>
     *   <li>调用 Handler 钩子（onBeforeExecute/onAfterExecute/onSuccess/onFailure/onCancel）</li>
     *   <li>超时监控守护线程（协作式 + 30s grace period + Future.cancel 强制中断）</li>
     *   <li>终态强制刷盘 + 互斥键释放</li>
     * </ul>
     *
     * @param taskId 任务ID
     */
    @Async("taskExecutor")
    public void executeAsync(String taskId) {
        TaskRecord record = taskRecordMapper.selectById(taskId);
        if (record == null) {
            log.warn("[TaskCenter] executeAsync: 任务不存在 {}", taskId);
            return;
        }

        // 1. 乐观锁更新 PENDING → RUNNING（ADR-020）
        LocalDateTime startedAt = LocalDateTime.now();
        int updated = taskRecordMapper.updateToRunning(taskId, startedAt);
        if (updated == 0) {
            log.info("[TaskCenter] 任务 {} 已被取消或被崩溃恢复标记，跳过执行", taskId);
            return;
        }

        // 2. 查找 Handler
        TaskHandler handler = handlerRegistry.get(record.getSource(), record.getTaskType());
        if (handler == null) {
            log.error("[TaskCenter] 任务 {} 的 Handler 已注销（插件可能已卸载），标记为 FAILED", taskId);
            updateToFailed(taskId, "Handler 已注销", null, startedAt);
            return;
        }

        // 3. 创建 TaskContextImpl
        TaskContextImpl ctx = new TaskContextImpl(
                taskId, record.getTaskType(), record.getSource(), record.getScopeKey(),
                taskRecordMapper, taskLogMapper, idGenerator, logFlushExecutor);

        // 4. 注册到 holder
        taskContextHolder.register(taskId, ctx);

        // 5. 检查 cancel 竞态（cancel 在 ctx 创建之前到达）
        AtomicBoolean preCancelFlag = cancelRequests.remove(taskId);
        if (preCancelFlag != null && preCancelFlag.get()) {
            ctx.markCancelled();
        }

        // 6. 反序列化 payload
        TaskPayload payload = deserializePayload(record.getPayload());

        // 7. 启动超时守护线程
        long timeoutMs = handler.getDefaultTimeoutMs();
        Thread workerThread = Thread.currentThread();
        Future<?> timeoutFuture = scheduleTimeoutWatch(ctx, timeoutMs, workerThread);

        // 8. 调用钩子 + 执行 Handler
        try {
            handler.onBeforeExecute(ctx, payload);

            TaskResult result;
            try {
                result = handler.execute(ctx, payload);
            } catch (InterruptedException ie) {
                // 强制中断（Future.cancel(true) 触发）
                Thread.currentThread().interrupt();
                handleInterruption(taskId, ctx, handler, payload, record, startedAt, "任务执行超时（强制中断）");
                return;
            } catch (Exception e) {
                // Handler 主动抛异常
                handleFailure(taskId, ctx, handler, payload, record, startedAt, e);
                return;
            }

            // Handler 正常返回，判断是否取消
            if (ctx.isCancelled()) {
                handleCancellation(taskId, ctx, handler, payload, record, startedAt);
            } else if (ctx.isTimeout()) {
                // Handler 优雅退出但已超时，标记 FAILED
                handleFailure(taskId, ctx, handler, payload, record, startedAt,
                        new TaskException("任务执行超时"));
            } else {
                handleSuccess(taskId, ctx, handler, payload, record, startedAt, result);
            }
        } finally {
            // 取消超时守护
            timeoutFuture.cancel(true);
            // 关闭上下文：强制刷盘剩余日志 + 关闭刷盘定时器 + 触发 500 条清理
            ctx.close();
            // 注销上下文
            taskContextHolder.unregister(taskId);
            // 二次确认互斥键释放（防止意外漏释放）
            taskMutexManager.removeByTaskId(taskId);
        }
    }

    // ==================== 内部方法：状态终结处理 ====================

    private void handleSuccess(String taskId, TaskContextImpl ctx, TaskHandler handler,
                                TaskPayload payload, TaskRecord record,
                                LocalDateTime startedAt, TaskResult result) {
        try {
            handler.onAfterExecute(ctx, payload, result);
            handler.onSuccess(ctx, payload, result);
        } catch (Exception e) {
            log.warn("[TaskCenter] 任务 {} onSuccess 钩子异常: {}", taskId, e.getMessage());
        }

        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = Duration.between(startedAt, completedAt).toMillis();

        String resultJson = serializeResult(result);
        String summary = safeGetResultSummary(handler, result);

        taskRecordMapper.updateToCompleted(taskId, resultJson, summary, completedAt, durationMs, completedAt);
        log.info("[TaskCenter] 任务完成: id={}, duration={}ms, summary={}", taskId, durationMs, summary);
    }

    private void handleFailure(String taskId, TaskContextImpl ctx, TaskHandler handler,
                                TaskPayload payload, TaskRecord record,
                                LocalDateTime startedAt, Throwable error) {
        try {
            handler.onAfterExecute(ctx, payload, null);
            handler.onFailure(ctx, payload, error);
        } catch (Exception e) {
            log.warn("[TaskCenter] 任务 {} onFailure 钩子异常: {}", taskId, e.getMessage());
        }

        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = Duration.between(startedAt, completedAt).toMillis();

        String errorMessage = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        String stackTrace = truncateStackTrace(getStackTrace(error));

        taskRecordMapper.updateToFailed(taskId, errorMessage, stackTrace, completedAt, durationMs, completedAt);
        log.warn("[TaskCenter] 任务失败: id={}, duration={}ms, err={}", taskId, durationMs, errorMessage);
    }

    private void handleInterruption(String taskId, TaskContextImpl ctx, TaskHandler handler,
                                     TaskPayload payload, TaskRecord record,
                                     LocalDateTime startedAt, String message) {
        try {
            handler.onAfterExecute(ctx, payload, null);
            handler.onFailure(ctx, payload, new TaskException(message));
        } catch (Exception e) {
            log.warn("[TaskCenter] 任务 {} onFailure 钩子异常: {}", taskId, e.getMessage());
        }

        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = Duration.between(startedAt, completedAt).toMillis();

        taskRecordMapper.updateToFailed(taskId, message, "强制中断", completedAt, durationMs, completedAt);
        log.warn("[TaskCenter] 任务强制中断: id={}, duration={}ms", taskId, durationMs);
    }

    private void handleCancellation(String taskId, TaskContextImpl ctx, TaskHandler handler,
                                     TaskPayload payload, TaskRecord record,
                                     LocalDateTime startedAt) {
        try {
            handler.onCancel(ctx, payload);
        } catch (Exception e) {
            log.warn("[TaskCenter] 任务 {} onCancel 钩子异常: {}", taskId, e.getMessage());
        }

        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = Duration.between(startedAt, completedAt).toMillis();

        // 仅在 RUNNING 状态时更新为 CANCELLED（避免误更新已变更状态）
        int updated = taskRecordMapper.updateToCancelledFromRunning(taskId, completedAt, durationMs, completedAt);
        if (updated == 0) {
            log.info("[TaskCenter] 任务 {} 状态已变更，跳过 CANCELLED 更新", taskId);
        } else {
            log.info("[TaskCenter] 任务取消: id={}, duration={}ms", taskId, durationMs);
        }
    }

    // ==================== 取消逻辑（供 TaskAdminServiceImpl 调用） ====================

    /**
     * 取消任务（核心逻辑，{@link #cancelMyOwn} 和 {@code TaskAdminServiceImpl.cancel} 共用）
     */
    public boolean doCancel(String taskId, TaskRecord record) {
        String status = record.getStatus();
        TaskStatus current = TaskStatus.valueOf(status);

        if (current.isTerminal()) {
            throw new TaskNotCancellableException(taskId, status);
        }

        if (current == TaskStatus.PENDING) {
            // PENDING：乐观更新为 CANCELLED，影响行数 = 0 表示已被 taskExecutor 取走
            int rows = taskRecordMapper.updateToCancelledFromPending(taskId, LocalDateTime.now());
            if (rows == 1) {
                taskMutexManager.removeByTaskId(taskId);
                handlerRegistry.removeTaskSourceIndex(taskId);
                log.info("[TaskCenter] 任务取消成功（PENDING）: id={}", taskId);
                return true;
            }
            // 已转为 RUNNING，继续走 RUNNING 取消流程
            log.info("[TaskCenter] 任务 {} 已转为 RUNNING，转入协作式取消", taskId);
        }

        // RUNNING：记录取消请求 + 标记 ctx
        cancelRequests.computeIfAbsent(taskId, k -> new AtomicBoolean(false)).set(true);
        TaskContextImpl ctx = taskContextHolder.get(taskId);
        if (ctx != null) {
            ctx.markCancelled();
        }
        log.info("[TaskCenter] 任务取消请求已接受（RUNNING）: id={}", taskId);
        return true;
    }

    // ==================== 超时守护 ====================

    /**
     * 调度超时守护线程（ADR-009 混合模式）
     *
     * <p>流程：
     * <ol>
     *   <li>等待 {@code timeoutMs}（Handler 默认超时）</li>
     *   <li>设置 {@code ctx.timeout=true}（协作式）</li>
     *   <li>等待 30s grace period</li>
     *   <li>仍未结束则 {@code workerThread.interrupt()} 强制中断</li>
     * </ol>
     *
     * @param ctx          任务上下文
     * @param timeoutMs    超时阈值（毫秒）
     * @param workerThread 执行 Handler 的工作线程（用于强制中断）
     * @return 守护 Future，可用于取消守护
     */
    private Future<?> scheduleTimeoutWatch(TaskContextImpl ctx, long timeoutMs, Thread workerThread) {
        if (timeoutMs <= 0) {
            // 不超时，返回已完成 Future
            return CompletableFuture.completedFuture(null);
        }
        return timeoutWatchExecutor.submit(() -> {
            try {
                // 1. 等待超时阈值
                Thread.sleep(timeoutMs);
                // 2. 协作式标志：Handler 在循环中检查 ctx.isTimeout() 主动退出
                ctx.markTimeout();
                log.info("[TaskCenter] 任务 {} 触发超时标志，等待 {}ms grace period",
                        ctx.getTaskId(), TIMEOUT_GRACE_PERIOD_MS);
                // 3. grace period：等待 Handler 优雅退出
                Thread.sleep(TIMEOUT_GRACE_PERIOD_MS);
                // 4. 仍未结束则强制中断工作线程
                if (workerThread.isAlive()) {
                    log.warn("[TaskCenter] 任务 {} 超过 grace period 仍未结束，强制中断工作线程",
                            ctx.getTaskId());
                    workerThread.interrupt();
                }
            } catch (InterruptedException e) {
                // 守护线程被取消（任务正常结束），正常退出
                Thread.currentThread().interrupt();
            }
        });
    }

    // ==================== 私有辅助方法 ====================

    private void validateSubmitRequest(TaskSubmitRequest request) {
        if (request == null) {
            throw new TaskException("提交请求不能为空");
        }
        if (request.getTaskType() == null || request.getTaskType().isBlank()) {
            throw new TaskException("taskType 不能为空");
        }
        if (request.getSource() == null || request.getSource().isBlank()) {
            throw new TaskException("source 不能为空");
        }
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.getBytes().length > PAYLOAD_MAX_BYTES) {
                throw new TaskPayloadTooLargeException(json.getBytes().length);
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new TaskException("payload 序列化失败: " + e.getMessage());
        }
    }

    private String serializeResult(TaskResult result) {
        if (result == null) {
            return null;
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("success", result.isSuccess());
            if (result.getData() != null && !result.getData().isEmpty()) {
                data.put("data", result.getData());
            }
            if (result.getMessage() != null) {
                data.put("message", result.getMessage());
            }
            String json = objectMapper.writeValueAsString(data);
            if (json.getBytes().length > RESULT_MAX_BYTES) {
                log.warn("[TaskCenter] result 超过 256KB，建议 Handler 将大对象存到外部存储");
                // 截断保护
                return "{\"success\":" + result.isSuccess() + ",\"message\":\"result 过大已截断\"}";
            }
            return json;
        } catch (JsonProcessingException e) {
            log.warn("[TaskCenter] result 序列化失败: {}", e.getMessage());
            return "{\"success\":" + result.isSuccess() + "}";
        }
    }

    private TaskPayload deserializePayload(String json) {
        return deserializePayloadForRetry(json);
    }

    /**
     * 反序列化 payload JSON（包级可见，供 TaskAdminServiceImpl 在 retry 时复用）
     *
     * @param json payload JSON 字符串
     * @return TaskPayload 实例
     */
    TaskPayload deserializePayloadForRetry(String json) {
        if (json == null || json.isBlank()) {
            return new TaskPayload(Collections.emptyMap());
        }
        try {
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return new TaskPayload(data);
        } catch (JsonProcessingException e) {
            log.warn("[TaskCenter] payload 反序列化失败: {}", e.getMessage());
            return new TaskPayload(Collections.emptyMap());
        }
    }

    private String computeMutexKey(TaskHandler handler, TaskPayload payload,
                                    String source, String taskType, String scopeKey) {
        String handlerKey = handler.getMutexKey(payload);
        if (handlerKey != null) {
            // 空字符串表示不互斥
            return handlerKey.isEmpty() ? null : handlerKey;
        }
        // 默认规则（ADR-011）
        if (scopeKey != null && !scopeKey.isBlank()) {
            return taskType + ":" + scopeKey;
        }
        return source + ":" + taskType;
    }

    private TaskRecord buildTaskRecord(String taskId, TaskSubmitRequest request,
                                        String source, String payloadJson, TaskHandler handler) {
        TaskRecord record = new TaskRecord();
        record.setId(taskId);
        record.setTaskType(request.getTaskType());
        record.setSource(source);
        record.setStatus(TaskStatus.PENDING.name());
        record.setSubmitter(request.getSubmitter() != null ? request.getSubmitter() : "SYSTEM");
        record.setScopeType(request.getScopeType() != null ? request.getScopeType() : "GLOBAL");
        record.setScopeKey(request.getScopeKey());
        record.setScopeName(request.getScopeName());
        record.setPayload(payloadJson);
        record.setProgress(0);
        record.setRetryCount(0);
        return record;
    }

    private void updateToFailed(String taskId, String errorMessage, String stackTrace,
                                 LocalDateTime startedAt) {
        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = Duration.between(startedAt, completedAt).toMillis();
        taskRecordMapper.updateToFailed(taskId, errorMessage, stackTrace, completedAt, durationMs, completedAt);
    }

    private String safeGetResultSummary(TaskHandler handler, TaskResult result) {
        try {
            return handler.getResultSummary(result);
        } catch (Exception e) {
            log.warn("[TaskCenter] getResultSummary 异常: {}", e.getMessage());
            return null;
        }
    }

    private String getStackTrace(Throwable error) {
        java.io.StringWriter sw = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private String truncateStackTrace(String stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        // 截断到 8KB 避免过大
        return stackTrace.length() > 8192 ? stackTrace.substring(0, 8192) + "\n... (truncated)" : stackTrace;
    }

    private LambdaQueryWrapper<TaskRecord> buildQueryWrapper(TaskQuery query) {
        LambdaQueryWrapper<TaskRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskRecord::getDeleted, 0);
        if (query.getSource() != null && !query.getSource().isBlank()) {
            wrapper.eq(TaskRecord::getSource, query.getSource());
        }
        if (query.getTaskType() != null && !query.getTaskType().isBlank()) {
            wrapper.eq(TaskRecord::getTaskType, query.getTaskType());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(TaskRecord::getStatus, query.getStatus());
        }
        if (query.getScopeKey() != null && !query.getScopeKey().isBlank()) {
            wrapper.eq(TaskRecord::getScopeKey, query.getScopeKey());
        }
        if (query.getSubmitter() != null && !query.getSubmitter().isBlank()) {
            wrapper.eq(TaskRecord::getSubmitter, query.getSubmitter());
        }
        if (query.getStartTime() != null) {
            wrapper.ge(TaskRecord::getCreateTime, query.getStartTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(TaskRecord::getCreateTime, query.getEndTime());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(TaskRecord::getTaskType, kw)
                    .or().like(TaskRecord::getScopeName, kw)
                    .or().like(TaskRecord::getErrorMessage, kw));
        }
        wrapper.orderByDesc(TaskRecord::getCreateTime);
        return wrapper;
    }

    private TaskVO convertToVO(TaskRecord record) {
        TaskVO vo = new TaskVO();
        vo.setId(record.getId());
        vo.setTaskType(record.getTaskType());
        vo.setSource(record.getSource());
        vo.setStatus(record.getStatus());
        vo.setSubmitter(record.getSubmitter());
        vo.setScopeType(record.getScopeType());
        vo.setScopeKey(record.getScopeKey());
        vo.setScopeName(record.getScopeName());
        vo.setPayload(deserializeJson(record.getPayload()));
        vo.setResult(deserializeJson(record.getResult()));
        vo.setResultSummary(record.getResultSummary());
        vo.setProgress(record.getProgress());
        vo.setProgressMessage(record.getProgressMessage());
        vo.setErrorMessage(record.getErrorMessage());
        // 仅 FAILED 状态返回 stackTrace（安全考虑）
        if (TaskStatus.FAILED.name().equals(record.getStatus())) {
            vo.setStackTrace(record.getStackTrace());
        }
        vo.setRetryCount(record.getRetryCount());
        vo.setParentTaskId(record.getParentTaskId());
        vo.setStartedAt(record.getStartedAt());
        vo.setCompletedAt(record.getCompletedAt());
        vo.setDurationMs(record.getDurationMs());
        vo.setCreateTime(record.getCreateTime());

        // 填充 Handler 元信息
        TaskHandler handler = handlerRegistry.get(record.getSource(), record.getTaskType());
        if (handler != null) {
            vo.setTaskTypeName(handler.getDisplayName());
            vo.setMaxRetryCount(handler.getMaxRetryCount());
            boolean retryable = handler.isRetryable()
                    && (record.getRetryCount() == null || record.getRetryCount() < handler.getMaxRetryCount());
            vo.setRetryable(retryable);
        } else {
            vo.setMaxRetryCount(0);
            vo.setRetryable(false);
        }
        return vo;
    }

    private Object deserializeJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private TaskLog convertLogToVO(com.gameplatform.entity.TaskLog entity) {
        TaskLog vo = new TaskLog();
        vo.setId(entity.getId());
        vo.setTaskId(entity.getTaskId());
        vo.setLevel(entity.getLevel());
        vo.setMessage(entity.getMessage());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
