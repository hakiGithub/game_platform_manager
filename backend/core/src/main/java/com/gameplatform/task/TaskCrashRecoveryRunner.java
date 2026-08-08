package com.gameplatform.task;

import com.gameplatform.config.TaskCenterProperties;
import com.gameplatform.entity.TaskRecord;
import com.gameplatform.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务中心崩溃恢复 Runner（ADR-007）
 *
 * <p>应用启动时执行：
 * <ol>
 *   <li>清空内存互斥键（{@link TaskMutexManager#clear}）+ TaskSourceIndex</li>
 *   <li>扫描所有 {@code status IN ('PENDING','RUNNING')} 的遗留任务</li>
 *   <li>批量更新为 {@code FAILED}，{@code error_message="应用重启，任务被中断"}</li>
 *   <li>清空运行中上下文持有器（{@link TaskContextHolder#clear}）</li>
 * </ol>
 *
 * <p><b>执行顺序</b>：{@code @Order(50)}，早于 {@link InstanceSyncStartupRunner}（Order=100）。
 * 互斥键必须在 InstanceSync 等可能提交任务的组件启动前清空。
 *
 * <p><b>幂等性</b>：恢复逻辑仅影响未终态任务，重复执行无副作用。
 * 应用启动只执行一次（由 Spring Boot 保证 ApplicationRunner 一次性）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(50)
public class TaskCrashRecoveryRunner implements ApplicationRunner {

    private final TaskRecordMapper taskRecordMapper;
    private final TaskMutexManager taskMutexManager;
    private final TaskHandlerRegistry handlerRegistry;
    private final TaskContextHolder taskContextHolder;
    private final TaskCenterProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getCrashRecovery().isEnabled()) {
            log.info("[TaskCenter] 崩溃恢复已禁用 (game-platform.task-center.crash-recovery.enabled=false)");
            return;
        }

        log.info("[TaskCenter] 启动崩溃恢复检查...");

        // 1. 清空内存互斥键（崩溃后内存已丢失，兜底确保从干净状态启动）
        int mutexSize = taskMutexManager.size();
        taskMutexManager.clear();
        handlerRegistry.clearTaskSourceIndex();
        taskContextHolder.clear();
        if (mutexSize > 0) {
            log.info("[TaskCenter] 清空 {} 个残留内存互斥键", mutexSize);
        }

        // 2. 查询所有未完成的任务（PENDING 或 RUNNING）
        List<TaskRecord> staleTasks = taskRecordMapper.selectUnfinishedTasks();
        if (staleTasks.isEmpty()) {
            log.info("[TaskCenter] 崩溃恢复完成，无遗留任务");
            return;
        }

        log.warn("[TaskCenter] 检测到 {} 个崩溃遗留任务，标记为 FAILED", staleTasks.size());

        // 3. 逐条更新为 FAILED（携带 errorMessage + completedAt + durationMs）
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (TaskRecord task : staleTasks) {
            try {
                recoverStaleTask(task, now);
                recovered++;
            } catch (Exception e) {
                log.error("[TaskCenter] 崩溃恢复: 任务 {} 更新失败: {}", task.getId(), e.getMessage());
            }
        }

        log.warn("[TaskCenter] 崩溃恢复完成，已标记 {} 个任务为 FAILED", recovered);
    }

    /**
     * 将单个遗留任务标记为 FAILED
     */
    private void recoverStaleTask(TaskRecord task, LocalDateTime now) {
        String originalStatus = task.getStatus();

        // 计算耗时：仅当有 startedAt 时计算
        Long durationMs = null;
        LocalDateTime startedAt = task.getStartedAt();
        if (startedAt != null) {
            try {
                durationMs = Duration.between(startedAt, now).toMillis();
            } catch (Exception e) {
                // 时间异常忽略
            }
        }

        // 直接调用 updateToFailed（不走乐观锁，因为遗留任务状态本就不确定）
        String errorMessage = "应用重启，任务被中断（原状态: " + originalStatus + "）";
        String stackTrace = null; // 崩溃恢复无堆栈
        int rows = taskRecordMapper.updateToFailed(
                task.getId(), errorMessage, stackTrace, now, durationMs, now);

        if (rows > 0) {
            log.warn("[TaskCenter] 崩溃恢复: taskId={}, type={}, source={}, scope={}, originalStatus={}",
                    task.getId(), task.getTaskType(), task.getSource(),
                    task.getScopeKey(), originalStatus);
        } else {
            // 影响行数 = 0 表示记录已被其他流程处理（理论不发生）
            log.info("[TaskCenter] 崩溃恢复: 任务 {} 状态已变更，跳过", task.getId());
        }
    }
}
