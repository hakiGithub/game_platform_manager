package com.gameplatform.task;

import com.gameplatform.enums.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TaskStatus} 状态机校验测试（ADR-030 / ADR-006）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link TaskStatus#isTerminal()} 三种终态判断</li>
 *   <li>{@link TaskStatus#canTransitionTo(TaskStatus)} 合法/非法流转校验</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("TaskStatus 状态机校验")
class TaskStatusTest {

    // ==================== isTerminal ====================

    @Nested
    @DisplayName("isTerminal() 终态判断")
    class IsTerminal {

        @Test
        @DisplayName("PENDING 不是终态")
        void pendingIsNotTerminal() {
            assertFalse(TaskStatus.PENDING.isTerminal());
        }

        @Test
        @DisplayName("RUNNING 不是终态")
        void runningIsNotTerminal() {
            assertFalse(TaskStatus.RUNNING.isTerminal());
        }

        @Test
        @DisplayName("COMPLETED 是终态")
        void completedIsTerminal() {
            assertTrue(TaskStatus.COMPLETED.isTerminal());
        }

        @Test
        @DisplayName("FAILED 是终态")
        void failedIsTerminal() {
            assertTrue(TaskStatus.FAILED.isTerminal());
        }

        @Test
        @DisplayName("CANCELLED 是终态")
        void cancelledIsTerminal() {
            assertTrue(TaskStatus.CANCELLED.isTerminal());
        }
    }

    // ==================== canTransitionTo ====================

    @Nested
    @DisplayName("canTransitionTo() 状态流转校验")
    class CanTransitionTo {

        @Test
        @DisplayName("PENDING -> RUNNING 合法")
        void pendingToRunning() {
            assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.RUNNING));
        }

        @Test
        @DisplayName("PENDING -> CANCELLED 合法（任务未开始即被取消）")
        void pendingToCancelled() {
            assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.CANCELLED));
        }

        @Test
        @DisplayName("PENDING -> FAILED 合法（崩溃恢复或 PENDING 超时）")
        void pendingToFailed() {
            assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.FAILED));
        }

        @Test
        @DisplayName("PENDING -> COMPLETED 非法（必须先经过 RUNNING）")
        void pendingToCompletedIllegal() {
            assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.COMPLETED));
        }

        @Test
        @DisplayName("RUNNING -> COMPLETED 合法（正常完成）")
        void runningToCompleted() {
            assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.COMPLETED));
        }

        @Test
        @DisplayName("RUNNING -> FAILED 合法（执行失败）")
        void runningToFailed() {
            assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.FAILED));
        }

        @Test
        @DisplayName("RUNNING -> CANCELLED 合法（协作式取消后退出）")
        void runningToCancelled() {
            assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.CANCELLED));
        }

        @Test
        @DisplayName("RUNNING -> PENDING 非法（不可回退）")
        void runningToPendingIllegal() {
            assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.PENDING));
        }

        @Test
        @DisplayName("COMPLETED -> 任意状态均非法（终态不可变）")
        void completedIsImmutable() {
            for (TaskStatus next : TaskStatus.values()) {
                assertFalse(TaskStatus.COMPLETED.canTransitionTo(next),
                        "COMPLETED 不应能流转到 " + next);
            }
        }

        @Test
        @DisplayName("FAILED -> 任意状态均非法（终态不可变，重试创建新任务而非变更）")
        void failedIsImmutable() {
            for (TaskStatus next : TaskStatus.values()) {
                assertFalse(TaskStatus.FAILED.canTransitionTo(next),
                        "FAILED 不应能流转到 " + next);
            }
        }

        @Test
        @DisplayName("CANCELLED -> 任意状态均非法（终态不可变，重试创建新任务而非变更）")
        void cancelledIsImmutable() {
            for (TaskStatus next : TaskStatus.values()) {
                assertFalse(TaskStatus.CANCELLED.canTransitionTo(next),
                        "CANCELLED 不应能流转到 " + next);
            }
        }
    }
}
