package com.gameplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.entity.TaskRecord;
import com.gameplatform.enums.TaskStatus;
import com.gameplatform.mapper.TaskLogMapper;
import com.gameplatform.mapper.TaskRecordMapper;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.task.*;
import com.gameplatform.service.impl.TaskServiceImpl;
import com.gameplatform.task.TaskContextHolder;
import com.gameplatform.task.TaskContextImpl;
import com.gameplatform.task.TaskHandlerRegistry;
import com.gameplatform.task.TaskLogFlushExecutor;
import com.gameplatform.task.TaskMutexManager;
import com.gameplatform.task.exception.TaskAlreadyRunningException;
import com.gameplatform.task.exception.TaskMaxRetryExceededException;
import com.gameplatform.task.exception.TaskNotCancellableException;
import com.gameplatform.task.exception.TaskNotRetryableException;
import com.gameplatform.task.exception.TaskTypeNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link TaskServiceImpl} 集成测试（ADR-034 测试策略）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>submit + executeAsync 全流程：PENDING → RUNNING → COMPLETED/FAILED</li>
 *   <li>钩子调用顺序：onSubmit → onBeforeExecute → execute → onAfterExecute → onSuccess/onFailure</li>
 *   <li>互斥检查：同 mutexKey 重复提交抛 TaskAlreadyRunningException</li>
 *   <li>取消流程：PENDING 直接 CANCELLED；RUNNING 协作式取消</li>
 *   <li>重试流程：FAILED 任务重试创建新任务，retryCount 累加，maxRetryCount 上限</li>
 *   <li>并发提交相同 mutexKey，仅一个成功</li>
 * </ul>
 *
 * <p><b>测试策略</b>：使用 Mockito mock 掉 Mapper 层；TaskServiceImpl.self 直接指向自身实例，
 * 使 {@code @Async} 自调用退化为同步调用（不走 Spring 代理），保证测试可重复且快速。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskServiceImpl 集成测试")
class TaskServiceIntegrationTest {

    @Mock
    private TaskRecordMapper taskRecordMapper;
    @Mock
    private TaskLogMapper taskLogMapper;
    @Mock
    private ExtensionIdGenerator idGenerator;

    private TaskHandlerRegistry handlerRegistry;
    private TaskMutexManager mutexManager;
    private TaskContextHolder contextHolder;
    private TaskLogFlushExecutor flushExecutor;
    private ObjectMapper objectMapper;

    private TaskServiceImpl taskService;

    /** 自增 taskId，模拟雪花 ID */
    private final AtomicInteger taskIdSeq = new AtomicInteger(0);

    /** 内存数据库，模拟 task_record 表的 CRUD 行为 */
    private final Map<String, TaskRecord> db = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        handlerRegistry = new TaskHandlerRegistry();
        mutexManager = new TaskMutexManager();
        contextHolder = new TaskContextHolder();
        flushExecutor = new TaskLogFlushExecutor();
        objectMapper = new ObjectMapper();
        db.clear();

        taskService = new TaskServiceImpl(
                taskRecordMapper, taskLogMapper, handlerRegistry,
                mutexManager, contextHolder, flushExecutor, idGenerator, objectMapper);
        // self 指向自身，@Async 退化为同步调用
        taskService.setSelf(taskService);

        // ID 生成器返回自增值
        when(idGenerator.nextId()).thenAnswer(inv -> "task-" + taskIdSeq.incrementAndGet());

        // ========== 内存数据库行为：让 mock mapper 表现得像真实 DB ==========
        // insert：存入内存（使用防御性副本，避免后续 update 调用修改原始参数对象，
        // 导致 Mockito argAt verify 时看到的是终态而非初始状态）
        when(taskRecordMapper.insert(any(TaskRecord.class))).thenAnswer(inv -> {
            TaskRecord r = inv.getArgument(0);
            db.put(r.getId(), copyOf(r));
            return 1;
        });
        // selectById：从内存读取
        when(taskRecordMapper.selectById(anyString())).thenAnswer(inv ->
                db.get(inv.getArgument(0)));
        // updateToRunning：乐观锁 PENDING → RUNNING
        when(taskRecordMapper.updateToRunning(anyString(), any())).thenAnswer(inv -> {
            TaskRecord r = db.get(inv.getArgument(0));
            if (r != null && "PENDING".equals(r.getStatus())) {
                r.setStatus("RUNNING");
                r.setStartedAt(inv.getArgument(1));
                return 1;
            }
            return 0;
        });
        // updateToCancelledFromPending：乐观锁 PENDING → CANCELLED
        when(taskRecordMapper.updateToCancelledFromPending(anyString(), any())).thenAnswer(inv -> {
            TaskRecord r = db.get(inv.getArgument(0));
            if (r != null && "PENDING".equals(r.getStatus())) {
                r.setStatus("CANCELLED");
                r.setCompletedAt(inv.getArgument(1));
                return 1;
            }
            return 0;
        });
        // updateToCancelledFromRunning：RUNNING → CANCELLED
        when(taskRecordMapper.updateToCancelledFromRunning(anyString(), any(), anyLong(), any()))
                .thenAnswer(inv -> {
            TaskRecord r = db.get(inv.getArgument(0));
            if (r != null && "RUNNING".equals(r.getStatus())) {
                r.setStatus("CANCELLED");
                r.setCompletedAt(inv.getArgument(1));
                r.setDurationMs(inv.getArgument(2));
                return 1;
            }
            return 0;
        });
        // updateToCompleted：→ COMPLETED
        when(taskRecordMapper.updateToCompleted(anyString(), anyString(), anyString(),
                any(), anyLong(), any())).thenAnswer(inv -> {
            TaskRecord r = db.get(inv.getArgument(0));
            if (r != null) {
                r.setStatus("COMPLETED");
                r.setResult(inv.getArgument(1));
                r.setResultSummary(inv.getArgument(2));
                r.setCompletedAt(inv.getArgument(3));
                r.setDurationMs(inv.getArgument(4));
                r.setProgress(100);
                return 1;
            }
            return 0;
        });
        // updateToFailed：→ FAILED
        when(taskRecordMapper.updateToFailed(anyString(), anyString(), anyString(),
                any(), anyLong(), any())).thenAnswer(inv -> {
            TaskRecord r = db.get(inv.getArgument(0));
            if (r != null) {
                r.setStatus("FAILED");
                r.setErrorMessage(inv.getArgument(1));
                r.setStackTrace(inv.getArgument(2));
                r.setCompletedAt(inv.getArgument(3));
                r.setDurationMs(inv.getArgument(4));
                return 1;
            }
            return 0;
        });
        // incrementRetryCount：retryCount + 1
        when(taskRecordMapper.incrementRetryCount(anyString())).thenAnswer(inv -> {
            TaskRecord r = db.get(inv.getArgument(0));
            if (r != null) {
                r.setRetryCount((r.getRetryCount() != null ? r.getRetryCount() : 0) + 1);
                return 1;
            }
            return 0;
        });
        // updateProgress：更新进度
        when(taskRecordMapper.updateProgress(anyString(), anyInt(), anyString(), any()))
                .thenAnswer(inv -> {
            TaskRecord r = db.get(inv.getArgument(0));
            if (r != null) {
                r.setProgress(inv.getArgument(1));
                r.setProgressMessage(inv.getArgument(2));
                return 1;
            }
            return 0;
        });
        // updateById：通用更新（retry 设置 parentTaskId 时使用）
        when(taskRecordMapper.updateById(any(TaskRecord.class))).thenAnswer(inv -> {
            TaskRecord r = inv.getArgument(0);
            db.put(r.getId(), copyOf(r));
            return 1;
        });
        // taskLogMapper.insert：日志刷盘默认返回 1
        when(taskLogMapper.insert(any(com.gameplatform.entity.TaskLog.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        flushExecutor.shutdown();
    }

    // ==================== 辅助方法 ====================

    /** 创建一个简单的成功 Handler，记录钩子调用顺序 */
    private TestHandler successHandler(String taskType) {
        return new TestHandler(taskType, true, 3, 0);
    }

    /** 创建一个失败的 Handler */
    private TestHandler failureHandler(String taskType) {
        TestHandler h = new TestHandler(taskType, true, 3, 0);
        h.shouldFail = true;
        return h;
    }

    private TaskSubmitRequest submitRequest(String taskType, String source, String scopeKey,
                                              Map<String, Object> payload) {
        TaskSubmitRequest.TaskSubmitRequestBuilder b = TaskSubmitRequest.builder()
                .taskType(taskType)
                .source(source)
                .scopeType("GLOBAL");
        if (scopeKey != null) {
            b.scopeKey(scopeKey);
        }
        if (payload != null) {
            b.payload(payload);
        }
        return b.build();
    }

    // ==================== 提交与执行 ====================

    @Nested
    @DisplayName("submit + executeAsync 全流程")
    class SubmitAndExecute {

        @Test
        @DisplayName("成功提交并执行：状态 PENDING → RUNNING → COMPLETED，钩子按序调用")
        void successFlow() throws Exception {
            TestHandler handler = successHandler("crawl");
            handlerRegistry.register("L4D2", "crawl", handler);

            String taskId = taskService.submit(submitRequest("crawl", "L4D2", null,
                    Map.of("crawlType", "FULL")));

            // 等待同步执行完成（self=instance，executeAsync 同步执行）
            assertEquals("task-1", taskId);

            // 验证状态机：PENDING 插入 → RUNNING 更新 → COMPLETED 更新
            verify(taskRecordMapper).insert(argThat(r ->
                    "task-1".equals(r.getId()) && "PENDING".equals(r.getStatus())));
            verify(taskRecordMapper).updateToRunning(eq("task-1"), any());
            verify(taskRecordMapper).updateToCompleted(eq("task-1"), anyString(), anyString(),
                    any(), anyLong(), any());

            // 验证钩子调用顺序
            assertEquals(List.of(
                    "onSubmit", "onBeforeExecute", "execute",
                    "onAfterExecute", "onSuccess"), handler.callOrder);

            // 互斥键已释放
            assertFalse(mutexManager.isHeld("L4D2:crawl"));
        }

        @Test
        @DisplayName("Handler.execute 抛异常：状态 PENDING → RUNNING → FAILED，调用 onFailure")
        void failureFlow() throws Exception {
            TestHandler handler = failureHandler("deploy");
            handlerRegistry.register("MAIN", "deploy", handler);

            String taskId = taskService.submit(submitRequest("deploy", "MAIN", null,
                    Map.of("instanceId", 55)));

            verify(taskRecordMapper).updateToFailed(eq(taskId), anyString(), anyString(),
                    any(), anyLong(), any());

            assertEquals(List.of(
                    "onSubmit", "onBeforeExecute", "execute",
                    "onAfterExecute", "onFailure"), handler.callOrder);

            assertFalse(mutexManager.isHeld("MAIN:deploy"));
        }

        @Test
        @DisplayName("Handler 未注册：抛 TaskTypeNotFoundException")
        void unregisteredHandler() {
            assertThrows(TaskTypeNotFoundException.class,
                    () -> taskService.submit(submitRequest("nonexistent", "MAIN", null, Map.of())));
        }

        @Test
        @DisplayName("onSubmit 钩子抛异常：阻止提交，互斥键未占用")
        void onSubmitThrowsBlocksSubmit() {
            TestHandler handler = successHandler("crawl");
            handler.onSubmitThrow = new IllegalArgumentException("参数错误");
            handlerRegistry.register("L4D2", "crawl", handler);

            assertThrows(IllegalArgumentException.class,
                    () -> taskService.submit(submitRequest("crawl", "L4D2", null, Map.of())));

            // 互斥键未占用
            assertFalse(mutexManager.isHeld("L4D2:crawl"));
            // task_record 未插入
            verify(taskRecordMapper, never()).insert(any());
        }

        @Test
        @DisplayName("持久化失败：释放互斥键")
        void persistFailureReleasesMutex() {
            TestHandler handler = successHandler("crawl");
            handlerRegistry.register("L4D2", "crawl", handler);
            when(taskRecordMapper.insert(any())).thenThrow(new RuntimeException("DB down"));

            assertThrows(RuntimeException.class,
                    () -> taskService.submit(submitRequest("crawl", "L4D2", null, Map.of())));

            // 持久化失败后互斥键应释放
            assertFalse(mutexManager.isHeld("L4D2:crawl"));
        }
    }

    // ==================== 互斥检查 ====================

    @Nested
    @DisplayName("互斥检查")
    class MutexCheck {

        @Test
        @DisplayName("同 source+taskType 重复提交抛 TaskAlreadyRunningException")
        void duplicateGlobalTask() throws Exception {
            // 使用阻塞 Handler，使第一个任务一直 RUNNING（self=instance 时 executeAsync 同步执行）
            final CountDownLatch blockLatch = new CountDownLatch(1);
            final CountDownLatch startedLatch = new CountDownLatch(1);
            TestHandler handler = new TestHandler("crawl", true, 3, 0) {
                @Override
                public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
                    callOrder.add("execute");
                    startedLatch.countDown();
                    // 阻塞直到 latch 释放，保持任务 RUNNING 状态
                    blockLatch.await();
                    return TaskResult.success();
                }
            };
            handlerRegistry.register("L4D2", "crawl", handler);

            // 在独立线程提交第一个任务（self=instance 时 submit 会同步阻塞在 executeAsync）
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                exec.submit(() -> taskService.submit(submitRequest("crawl", "L4D2", null, Map.of())));

                // 等待 handler.execute 开始（任务进入 RUNNING）
                assertTrue(startedLatch.await(5, TimeUnit.SECONDS));

                // 第二个相同 mutexKey 应失败
                assertThrows(TaskAlreadyRunningException.class,
                        () -> taskService.submit(submitRequest("crawl", "L4D2", null, Map.of())));

                // 互斥键仍占用
                assertTrue(mutexManager.isHeld("L4D2:crawl"));

                // 释放阻塞，让第一个任务正常完成
                blockLatch.countDown();
            } finally {
                exec.shutdownNow();
            }
        }

        @Test
        @DisplayName("同 taskType 不同 scopeKey 不互斥")
        void differentScopeNotMutex() {
            TestHandler handler = successHandler("deploy");
            handlerRegistry.register("MAIN", "deploy", handler);

            // 不同 scopeKey 各自独立
            assertDoesNotThrow(() -> taskService.submit(
                    submitRequest("deploy", "MAIN", "instance-1", Map.of())));
            assertDoesNotThrow(() -> taskService.submit(
                    submitRequest("deploy", "MAIN", "instance-2", Map.of())));
        }

        @Test
        @DisplayName("Handler.getMutexKey 返回空字符串表示不互斥")
        void emptyMutexKeyMeansNoMutex() {
            TestHandler handler = successHandler("export");
            handler.customMutexKey = "";
            handlerRegistry.register("L4D2", "export", handler);

            // 即使 source+taskType 相同也不互斥
            assertDoesNotThrow(() -> taskService.submit(
                    submitRequest("export", "L4D2", null, Map.of())));
            assertDoesNotThrow(() -> taskService.submit(
                    submitRequest("export", "L4D2", null, Map.of())));
        }

        @Test
        @DisplayName("Handler.getMutexKey 返回自定义键，相同键互斥")
        void customMutexKey() throws Exception {
            // 使用阻塞 Handler，使第一个任务保持 RUNNING
            final CountDownLatch blockLatch = new CountDownLatch(1);
            final CountDownLatch startedLatch = new CountDownLatch(1);
            TestHandler handler = new TestHandler("backup", true, 3, 0) {
                @Override
                public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
                    callOrder.add("execute");
                    startedLatch.countDown();
                    blockLatch.await();
                    return TaskResult.success();
                }
            };
            handler.customMutexKey = "backup:host1+instance1";
            handlerRegistry.register("MAIN", "backup", handler);

            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                // 第一次提交成功（在独立线程，因为 executeAsync 同步阻塞）
                exec.submit(() -> taskService.submit(
                        submitRequest("backup", "MAIN", null, Map.of())));

                // 等待 handler.execute 开始
                assertTrue(startedLatch.await(5, TimeUnit.SECONDS));

                // 相同自定义键应互斥（无需替换 handler，mutexKey 来自 payload 无关）
                assertThrows(TaskAlreadyRunningException.class,
                        () -> taskService.submit(
                                submitRequest("backup", "MAIN", null, Map.of())));

                // 释放阻塞
                blockLatch.countDown();
            } finally {
                exec.shutdownNow();
            }
        }
    }

    // ==================== 取消流程 ====================

    @Nested
    @DisplayName("取消流程")
    class Cancel {

        @Test
        @DisplayName("终态任务取消抛 TaskNotCancellableException")
        void cancelTerminalTask() {
            TestHandler handler = successHandler("crawl");
            handlerRegistry.register("L4D2", "crawl", handler);
            String taskId = taskService.submit(submitRequest("crawl", "L4D2", null, Map.of()));

            // 任务已完成（同步执行），状态为 COMPLETED
            TaskRecord record = new TaskRecord();
            record.setId(taskId);
            record.setStatus("COMPLETED");
            when(taskRecordMapper.selectById(taskId)).thenReturn(record);

            assertThrows(TaskNotCancellableException.class,
                    () -> taskService.cancelMyOwn(taskId));
        }

        @Test
        @DisplayName("PENDING 任务取消：乐观更新为 CANCELLED，释放互斥键")
        void cancelPendingTask() {
            TestHandler handler = new TestHandler("crawl", true, 3, Long.MAX_VALUE);
            handlerRegistry.register("L4D2", "crawl", handler);

            // 让 updateToRunning 返回 0（任务还在 PENDING，未被 taskExecutor 取走）
            // 这会覆盖内存数据库的默认行为，使 executeAsync 跳过执行
            when(taskRecordMapper.updateToRunning(anyString(), any())).thenReturn(0);

            String taskId = taskService.submit(submitRequest("crawl", "L4D2", null, Map.of()));

            // 任务仍在 PENDING（updateToRunning 返回 0，executeAsync 已跳过执行）
            // 内存数据库中 selectById 返回 PENDING 记录，updateToCancelledFromPending 正常工作
            boolean ok = taskService.cancelMyOwn(taskId);

            assertTrue(ok);
            verify(taskRecordMapper).updateToCancelledFromPending(eq(taskId), any());
            // 互斥键释放
            assertFalse(mutexManager.isHeld("L4D2:crawl"));
        }

        @Test
        @DisplayName("RUNNING 任务取消：标记 ctx.cancelled，Handler 检查后退出")
        void cancelRunningTask() throws Exception {
            final CountDownLatch startedLatch = new CountDownLatch(1);
            final AtomicReference<TaskContext> ctxRef = new AtomicReference<>();
            // 在 insert 时捕获 taskId（submit 返回前 executeAsync 同步执行，无法从返回值获取）
            final AtomicReference<String> taskIdRef = new AtomicReference<>();

            TestHandler handler = new TestHandler("crawl", true, 3, 0) {
                @Override
                public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
                    ctxRef.set(context);
                    startedLatch.countDown();
                    callOrder.add("execute");
                    // 模拟循环等待取消
                    for (int i = 0; i < 100; i++) {
                        if (context.isCancelled()) {
                            return TaskResult.failure("任务已取消");
                        }
                        Thread.sleep(50);
                    }
                    return TaskResult.success();
                }
            };
            handlerRegistry.register("L4D2", "crawl", handler);

            // 覆盖 insert：同时捕获 taskId 到 taskIdRef（在 executeAsync 调用前）
            when(taskRecordMapper.insert(any(TaskRecord.class))).thenAnswer(inv -> {
                TaskRecord r = inv.getArgument(0);
                taskIdRef.set(r.getId());
                db.put(r.getId(), r);
                return 1;
            });

            // 使用独立线程执行 submit（self=instance 时 executeAsync 同步执行，会阻塞）
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                exec.submit(() -> {
                    taskService.submit(submitRequest("crawl", "L4D2", null, Map.of()));
                });

                // 等待 execute 开始（handler.execute 内部 countDown）
                assertTrue(startedLatch.await(5, TimeUnit.SECONDS));

                // 从 insert 捕获的 taskId（insert 在 executeAsync 之前调用）
                String taskId = taskIdRef.get();
                assertNotNull(taskId, "taskId 应在 insert 时被捕获");

                // 内存数据库中状态已是 RUNNING（updateToRunning 已更新）
                boolean ok = taskService.cancelMyOwn(taskId);
                assertTrue(ok);

                // 等待 Handler 检测到 isCancelled 并退出
                Thread.sleep(500);

                // ctx.cancelled 已设置
                assertNotNull(ctxRef.get());
                assertTrue(ctxRef.get().isCancelled());

                // onCancel 钩子被调用
                assertTrue(handler.callOrder.contains("onCancel"));
            } finally {
                exec.shutdownNow();
            }
        }
    }

    // ==================== 重试流程 ====================

    @Nested
    @DisplayName("重试流程")
    class Retry {

        @Test
        @DisplayName("重试成功：创建新任务，原任务 retryCount +1，原状态不变")
        void retrySuccess() {
            TestHandler handler = successHandler("crawl");
            handlerRegistry.register("L4D2", "crawl", handler);

            // 模拟原任务为 FAILED
            TaskRecord original = new TaskRecord();
            original.setId("original-1");
            original.setTaskType("crawl");
            original.setSource("L4D2");
            original.setStatus("FAILED");
            original.setRetryCount(0);
            original.setScopeType("GLOBAL");
            original.setPayload("{\"crawlType\":\"FULL\"}");
            when(taskRecordMapper.selectById("original-1")).thenReturn(original);
            when(taskRecordMapper.incrementRetryCount("original-1")).thenReturn(1);

            // 通过 TaskAdminServiceImpl 调用 retry
            com.gameplatform.service.impl.TaskAdminServiceImpl adminService =
                    new com.gameplatform.service.impl.TaskAdminServiceImpl(
                            taskRecordMapper, taskLogMapper, handlerRegistry,
                            mutexManager, taskService);

            String newTaskId = adminService.retry("original-1");

            assertNotNull(newTaskId);
            assertNotEquals("original-1", newTaskId);
            verify(taskRecordMapper).incrementRetryCount("original-1");

            // onRetry 钩子被调用
            assertTrue(handler.callOrder.contains("onRetry"));

            // 新任务与原任务关联（parentTaskId）
            verify(taskRecordMapper, atLeastOnce()).updateById(argThat(r ->
                    "original-1".equals(r.getParentTaskId())));
        }

        @Test
        @DisplayName("重试非终态任务抛 TaskNotRetryableException")
        void retryNonTerminalThrows() {
            TestHandler handler = successHandler("crawl");
            handlerRegistry.register("L4D2", "crawl", handler);

            TaskRecord running = new TaskRecord();
            running.setId("running-1");
            running.setStatus("RUNNING");
            when(taskRecordMapper.selectById("running-1")).thenReturn(running);

            com.gameplatform.service.impl.TaskAdminServiceImpl adminService =
                    new com.gameplatform.service.impl.TaskAdminServiceImpl(
                            taskRecordMapper, taskLogMapper, handlerRegistry,
                            mutexManager, taskService);

            assertThrows(TaskNotRetryableException.class,
                    () -> adminService.retry("running-1"));
        }

        @Test
        @DisplayName("Handler.isRetryable=false 抛 TaskNotRetryableException")
        void retryNonRetryableHandler() {
            TestHandler handler = successHandler("deploy");
            handler.retryable = false;
            handlerRegistry.register("MAIN", "deploy", handler);

            TaskRecord failed = new TaskRecord();
            failed.setId("failed-1");
            failed.setTaskType("deploy");
            failed.setSource("MAIN");
            failed.setStatus("FAILED");
            failed.setRetryCount(0);
            failed.setPayload("{}");
            when(taskRecordMapper.selectById("failed-1")).thenReturn(failed);

            com.gameplatform.service.impl.TaskAdminServiceImpl adminService =
                    new com.gameplatform.service.impl.TaskAdminServiceImpl(
                            taskRecordMapper, taskLogMapper, handlerRegistry,
                            mutexManager, taskService);

            assertThrows(TaskNotRetryableException.class,
                    () -> adminService.retry("failed-1"));
        }

        @Test
        @DisplayName("超过 maxRetryCount 抛 TaskMaxRetryExceededException")
        void retryExceedsMax() {
            TestHandler handler = successHandler("crawl");
            handler.maxRetryCount = 2;
            handlerRegistry.register("L4D2", "crawl", handler);

            TaskRecord failed = new TaskRecord();
            failed.setId("failed-2");
            failed.setTaskType("crawl");
            failed.setSource("L4D2");
            failed.setStatus("FAILED");
            failed.setRetryCount(2); // 已重试 2 次
            failed.setPayload("{}");
            when(taskRecordMapper.selectById("failed-2")).thenReturn(failed);

            com.gameplatform.service.impl.TaskAdminServiceImpl adminService =
                    new com.gameplatform.service.impl.TaskAdminServiceImpl(
                            taskRecordMapper, taskLogMapper, handlerRegistry,
                            mutexManager, taskService);

            assertThrows(TaskMaxRetryExceededException.class,
                    () -> adminService.retry("failed-2"));
        }
    }

    // ==================== 并发测试 ====================

    @Nested
    @DisplayName("并发竞争")
    class Concurrency {

        @Test
        @DisplayName("10 个线程并发提交相同 mutexKey，仅 1 个成功")
        void concurrentSubmitSameMutex() throws Exception {
            // 使用阻塞 Handler，使任务保持 RUNNING
            TestHandler handler = new TestHandler("crawl", true, 3, Long.MAX_VALUE);
            handlerRegistry.register("L4D2", "crawl", handler);

            int threadCount = 10;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger exceptionCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        taskService.submit(submitRequest("crawl", "L4D2", null, Map.of()));
                        successCount.incrementAndGet();
                    } catch (TaskAlreadyRunningException e) {
                        exceptionCount.incrementAndGet();
                    } catch (Exception e) {
                        // 其他异常计数
                        exceptionCount.incrementAndGet();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(doneGate.await(10, TimeUnit.SECONDS), "所有线程应在 10s 内完成");
            pool.shutdownNow();

            assertEquals(1, successCount.get(), "仅一个线程应成功提交");
            assertEquals(threadCount - 1, exceptionCount.get());
        }
    }

    // ==================== 查询 ====================

    @Nested
    @DisplayName("查询")
    class Query {

        @Test
        @DisplayName("getTask 不存在抛 TaskNotFoundException")
        void getTaskNotFound() {
            when(taskRecordMapper.selectById("missing")).thenReturn(null);
            assertThrows(com.gameplatform.task.exception.TaskNotFoundException.class,
                    () -> taskService.getTask("missing"));
        }

        @Test
        @DisplayName("listTasks 返回分页结果，taskTypeName 从 Handler 填充")
        void listTasks() {
            // 先注册 Handler，convertToVO 时才能填充 taskTypeName
            handlerRegistry.register("L4D2", "crawl", successHandler("crawl"));

            TaskRecord r1 = new TaskRecord();
            r1.setId("t1");
            r1.setTaskType("crawl");
            r1.setSource("L4D2");
            r1.setStatus("COMPLETED");
            r1.setProgress(100);
            r1.setPayload("{}");
            r1.setRetryCount(0);
            r1.setCreateTime(LocalDateTime.now());

            com.baomidou.mybatisplus.extension.plugins.pagination.Page<TaskRecord> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
            page.setRecords(List.of(r1));
            page.setTotal(1);
            when(taskRecordMapper.selectPage(any(), any())).thenReturn(page);

            TaskQuery query = TaskQuery.builder()
                    .source("L4D2")
                    .page(1)
                    .size(20)
                    .build();

            PageResult<TaskVO> result = taskService.listTasks(query);
            assertEquals(1, result.getTotal());
            assertEquals(1, result.getRecords().size());
            assertEquals("t1", result.getRecords().get(0).getId());
            // taskTypeName 应从 Handler.getDisplayName 填充
            assertEquals("地图爬取", result.getRecords().get(0).getTaskTypeName());
            // retryable / maxRetryCount 应从 Handler 填充
            assertEquals(3, result.getRecords().get(0).getMaxRetryCount());
            assertTrue(result.getRecords().get(0).getRetryable());
        }
    }

    // ==================== 测试 Handler 实现 ====================

    /**
     * 测试用 Handler，记录钩子调用顺序，可配置超时/失败/取消行为。
     */
    static class TestHandler implements TaskHandler {
        final String type;
        final String displayName;
        boolean retryable;
        int maxRetryCount;
        long timeoutMs;
        String customMutexKey = null;
        boolean shouldFail = false;
        final List<String> callOrder = Collections.synchronizedList(new ArrayList<>());
        RuntimeException onSubmitThrow = null;

        TestHandler(String type, boolean retryable, int maxRetryCount, long timeoutMs) {
            this.type = type;
            this.displayName = "测试任务-" + type;
            this.retryable = retryable;
            this.maxRetryCount = maxRetryCount;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getDisplayName() {
            // 为 listTasks 测试提供固定中文名
            return "crawl".equals(type) ? "地图爬取" : displayName;
        }

        @Override
        public boolean isRetryable() {
            return retryable;
        }

        @Override
        public int getMaxRetryCount() {
            return maxRetryCount;
        }

        @Override
        public long getDefaultTimeoutMs() {
            return timeoutMs;
        }

        @Override
        public String getMutexKey(TaskPayload payload) {
            return customMutexKey;
        }

        @Override
        public void onSubmit(TaskSubmitContext ctx) {
            callOrder.add("onSubmit");
            if (onSubmitThrow != null) {
                throw onSubmitThrow;
            }
        }

        @Override
        public void onBeforeExecute(TaskContext context, TaskPayload payload) {
            callOrder.add("onBeforeExecute");
        }

        @Override
        public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
            callOrder.add("execute");
            if (shouldFail) {
                throw new RuntimeException("Handler 故意失败");
            }
            return TaskResult.success(Map.of("processed", 1), "成功");
        }

        @Override
        public void onAfterExecute(TaskContext context, TaskPayload payload, TaskResult result) {
            callOrder.add("onAfterExecute");
        }

        @Override
        public void onSuccess(TaskContext context, TaskPayload payload, TaskResult result) {
            callOrder.add("onSuccess");
        }

        @Override
        public void onFailure(TaskContext context, TaskPayload payload, Throwable error) {
            callOrder.add("onFailure");
        }

        @Override
        public void onCancel(TaskContext context, TaskPayload payload) {
            callOrder.add("onCancel");
        }

        @Override
        public void onRetry(TaskContext context, TaskPayload payload) {
            callOrder.add("onRetry");
        }

        @Override
        public String getResultSummary(TaskResult result) {
            if (result == null || !result.isSuccess()) {
                return result != null ? result.getMessage() : null;
            }
            return "测试结果摘要";
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建 TaskRecord 的防御性副本，用于内存数据库存储。
     *
     * <p>避免 update 方法修改 store 中的对象时影响 Mockito 捕获的原始参数对象
     * （argThat 在 verify 时检查的是参数引用，若对象被修改则验证失败）。
     */
    private static TaskRecord copyOf(TaskRecord src) {
        TaskRecord dst = new TaskRecord();
        dst.setId(src.getId());
        dst.setTaskType(src.getTaskType());
        dst.setSource(src.getSource());
        dst.setStatus(src.getStatus());
        dst.setSubmitter(src.getSubmitter());
        dst.setScopeType(src.getScopeType());
        dst.setScopeKey(src.getScopeKey());
        dst.setScopeName(src.getScopeName());
        dst.setPayload(src.getPayload());
        dst.setResult(src.getResult());
        dst.setResultSummary(src.getResultSummary());
        dst.setProgress(src.getProgress());
        dst.setProgressMessage(src.getProgressMessage());
        dst.setErrorMessage(src.getErrorMessage());
        dst.setStackTrace(src.getStackTrace());
        dst.setRetryCount(src.getRetryCount());
        dst.setParentTaskId(src.getParentTaskId());
        dst.setStartedAt(src.getStartedAt());
        dst.setCompletedAt(src.getCompletedAt());
        dst.setDurationMs(src.getDurationMs());
        dst.setCreateTime(src.getCreateTime());
        dst.setUpdateTime(src.getUpdateTime());
        return dst;
    }
}
