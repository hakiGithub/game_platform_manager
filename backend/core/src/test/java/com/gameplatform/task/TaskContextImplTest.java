package com.gameplatform.task;

import com.gameplatform.entity.TaskLog;
import com.gameplatform.mapper.TaskLogMapper;
import com.gameplatform.mapper.TaskRecordMapper;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link TaskContextImpl} 进度节流、日志缓冲、取消/超时标志测试
 * （ADR-014 / ADR-019 / ADR-023 / ADR-010）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>进度节流：相同 percent 忽略、1s 内仅更新内存、≥1s 或 100% 强制刷盘</li>
 *   <li>日志缓冲：加入 logBuffer，由共享刷盘定时器/终态触发批量刷盘</li>
 *   <li>close() 幂等、强制刷盘剩余日志、触发 500 条上限清理</li>
 *   <li>isCancelled / isTimeout 标志位</li>
 *   <li>reportProgress 范围裁剪（<0 / >100）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskContextImpl 进度节流与日志缓冲")
class TaskContextImplTest {

    @Mock
    private TaskRecordMapper taskRecordMapper;
    @Mock
    private TaskLogMapper taskLogMapper;
    @Mock
    private ExtensionIdGenerator idGenerator;

    private TaskLogFlushExecutor flushExecutor;
    private TaskContextImpl ctx;

    @BeforeEach
    void setUp() {
        // 真实的 flushExecutor（共享 ScheduledExecutorService，1s 周期）
        flushExecutor = new TaskLogFlushExecutor();
        when(idGenerator.nextId()).thenAnswer(inv -> "log-" + System.nanoTime());

        ctx = new TaskContextImpl(
                "task-001", "crawl", "L4D2", "scope-55",
                taskRecordMapper, taskLogMapper, idGenerator, flushExecutor);
    }

    @AfterEach
    void tearDown() {
        // 关闭 ctx：取消刷盘定时器、强制刷盘剩余日志
        ctx.close();
        flushExecutor.shutdown();
    }

    // ==================== 进度节流 ====================

    @Nested
    @DisplayName("reportProgress 进度节流")
    class ProgressThrottle {

        @Test
        @DisplayName("相同 percent 不写 DB")
        void samePercentIgnored() {
            ctx.reportProgress(50, "first");
            // 重置 mock，再次相同 percent 应不调用 mapper
            reset(taskRecordMapper);

            ctx.reportProgress(50, "second");

            verify(taskRecordMapper, never())
                    .updateProgress(eq("task-001"), anyInt(), anyString(), any());
        }

        @Test
        @DisplayName("不同 percent 距上次 <1s 仅更新内存")
        void withinThrottleOnlyMemory() {
            ctx.reportProgress(30, "thirty");
            reset(taskRecordMapper);

            // 立即再次上报不同 percent（< 1s）
            ctx.reportProgress(40, "forty");

            // 内存中有 pendingProgress=40，但 DB 未写入
            verify(taskRecordMapper, never())
                    .updateProgress(eq("task-001"), eq(40), anyString(), any());
        }

        @Test
        @DisplayName("percent=100 强制刷盘，无视节流")
        void hundredForcesFlush() {
            // 先占用一次，建立 lastReportedTime
            ctx.reportProgress(50, "fifty");
            reset(taskRecordMapper);

            // 立即上报 100，即使 <1s 也应强制刷盘
            ctx.reportProgress(100, "done");

            verify(taskRecordMapper, times(1))
                    .updateProgress(eq("task-001"), eq(100), eq("done"), any());
        }

        @Test
        @DisplayName("percent > 100 裁剪为 100")
        void percentClampedToHundred() {
            ctx.reportProgress(150, "over");

            verify(taskRecordMapper, atLeastOnce())
                    .updateProgress(eq("task-001"), eq(100), eq("over"), any());
        }

        @Test
        @DisplayName("percent < 0 裁剪为 0")
        void percentClampedToZero() {
            // -10 裁剪为 0，因 0 != 100 且距构造 <1s，仅更新内存（pendingProgress=0）
            ctx.reportProgress(-10, "under");

            // 通过 flushPendingProgress 强制刷盘挂起值，绕过节流验证裁剪结果
            ctx.flushPendingProgress();

            verify(taskRecordMapper, atLeastOnce())
                    .updateProgress(eq("task-001"), eq(0), eq("under"), any());
        }

        @Test
        @DisplayName("close 后 reportProgress 不再写 DB")
        void afterCloseNoFlush() {
            ctx.close();
            reset(taskRecordMapper);

            ctx.reportProgress(50, "post-close");

            verify(taskRecordMapper, never())
                    .updateProgress(anyString(), anyInt(), anyString(), any());
        }
    }

    // ==================== 日志缓冲 ====================

    @Nested
    @DisplayName("log 日志缓冲与刷盘")
    class LogBuffering {

        @Test
        @DisplayName("log 加入缓冲，close 时强制刷盘")
        void closeFlushesRemainingLogs() {
            ctx.log("INFO", "first log");
            ctx.log("WARN", "second log");
            ctx.log("ERROR", "third log");

            // 缓冲中，未刷盘
            verify(taskLogMapper, never()).insert(any(TaskLog.class));

            // close 触发强制刷盘
            ctx.close();

            verify(taskLogMapper, atLeast(3)).insert(any(TaskLog.class));
        }

        @Test
        @DisplayName("null 消息不加入缓冲")
        void nullMessageSkipped() {
            ctx.log(null);
            ctx.log("INFO", null);

            ctx.close();

            verify(taskLogMapper, never()).insert(any(TaskLog.class));
        }

        @Test
        @DisplayName("level 为 null 时回退为 INFO")
        void nullLevelDefaultsToInfo() {
            ctx.log(null, "msg");

            ctx.close();

            ArgumentCaptor<TaskLog> captor = ArgumentCaptor.forClass(TaskLog.class);
            verify(taskLogMapper).insert(captor.capture());
            assertEquals("INFO", captor.getValue().getLevel());
        }

        @Test
        @DisplayName("log(String) 默认 INFO 级别")
        void logStringDefaultsToInfo() {
            ctx.log("simple message");

            ctx.close();

            ArgumentCaptor<TaskLog> captor = ArgumentCaptor.forClass(TaskLog.class);
            verify(taskLogMapper).insert(captor.capture());
            assertEquals("INFO", captor.getValue().getLevel());
            assertEquals("simple message", captor.getValue().getMessage());
            assertEquals("task-001", captor.getValue().getTaskId());
        }

        @Test
        @DisplayName("日志设置 createTime 与 id")
        void logSetsCreateTimeAndId() {
            when(idGenerator.nextId()).thenReturn("log-id-123");

            ctx.log("test");
            ctx.close();

            ArgumentCaptor<TaskLog> captor = ArgumentCaptor.forClass(TaskLog.class);
            verify(taskLogMapper).insert(captor.capture());
            assertEquals("log-id-123", captor.getValue().getId());
            assertNotNull(captor.getValue().getCreateTime());
        }
    }

    // ==================== close 幂等性 + 500 条清理 ====================

    @Nested
    @DisplayName("close 幂等性与日志上限清理")
    class CloseIdempotent {

        @Test
        @DisplayName("多次 close 仅触发一次清理")
        void multipleCloseSafe() {
            ctx.log("log1");
            ctx.close();

            // 再次 close 不应再触发清理
            ctx.close();
            ctx.close();

            verify(taskLogMapper, times(1)).deleteOldLogs("task-001", 500);
        }

        @Test
        @DisplayName("close 触发 500 条日志上限清理")
        void closeTriggersLogCap() {
            ctx.close();
            verify(taskLogMapper, times(1)).deleteOldLogs("task-001", 500);
        }

        @Test
        @DisplayName("close 后 deleteOldLogs 抛异常不传播")
        void deleteOldLogsFailureSwallowed() {
            doThrow(new RuntimeException("DB down"))
                    .when(taskLogMapper).deleteOldLogs(anyString(), anyInt());

            // 不应抛异常
            assertDoesNotThrow(() -> ctx.close());
        }
    }

    // ==================== 取消/超时标志 ====================

    @Nested
    @DisplayName("isCancelled / isTimeout 标志位")
    class CancelTimeoutFlags {

        @Test
        @DisplayName("初始状态：未取消、未超时")
        void initialState() {
            assertFalse(ctx.isCancelled());
            assertFalse(ctx.isTimeout());
        }

        @Test
        @DisplayName("markCancelled 后 isCancelled=true")
        void markCancelledSetsFlag() {
            ctx.markCancelled();
            assertTrue(ctx.isCancelled());
        }

        @Test
        @DisplayName("markTimeout 后 isTimeout=true")
        void markTimeoutSetsFlag() {
            ctx.markTimeout();
            assertTrue(ctx.isTimeout());
        }

        @Test
        @DisplayName("两个标志独立")
        void flagsIndependent() {
            ctx.markCancelled();
            assertTrue(ctx.isCancelled());
            assertFalse(ctx.isTimeout());

            ctx.markTimeout();
            assertTrue(ctx.isCancelled());
            assertTrue(ctx.isTimeout());
        }
    }

    // ==================== 元数据访问 ====================

    @Nested
    @DisplayName("元数据访问")
    class Metadata {

        @Test
        @DisplayName("getTaskId 返回构造时传入的 ID")
        void getTaskId() {
            assertEquals("task-001", ctx.getTaskId());
        }

        @Test
        @DisplayName("getTaskType 返回构造时传入的类型")
        void getTaskType() {
            assertEquals("crawl", ctx.getTaskType());
        }

        @Test
        @DisplayName("getSource 返回构造时传入的来源")
        void getSource() {
            assertEquals("L4D2", ctx.getSource());
        }

        @Test
        @DisplayName("getScopeKey 返回构造时传入的 scope")
        void getScopeKey() {
            assertEquals("scope-55", ctx.getScopeKey());
        }
    }

    // ==================== flushPendingProgress ====================

    @Nested
    @DisplayName("flushPendingProgress 主动刷盘")
    class FlushPendingProgress {

        @Test
        @DisplayName("无挂起进度时不写 DB")
        void noPendingNoFlush() {
            ctx.flushPendingProgress();
            verify(taskRecordMapper, never())
                    .updateProgress(anyString(), anyInt(), anyString(), any());
        }

        @Test
        @DisplayName("有挂起进度时写入 DB")
        void pendingFlushed() {
            // 1. 占用 lastReportedPercent=30
            ctx.reportProgress(30, "thirty");
            // 2. 立即更新内存 pendingProgress=70（<1s 未刷盘）
            ctx.reportProgress(70, "seventy");
            reset(taskRecordMapper);

            // 主动刷盘
            ctx.flushPendingProgress();

            verify(taskRecordMapper, atLeastOnce())
                    .updateProgress(eq("task-001"), eq(70), eq("seventy"), any());
        }
    }
}
