package com.gameplatform.task;

import com.gameplatform.config.TaskCenterProperties;
import com.gameplatform.entity.TaskRecord;
import com.gameplatform.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PENDING 超时定时检查（ADR-017）
 *
 * <p>每分钟扫描一次，将 {@code status='PENDING' AND create_time < now()-5min} 的任务标记为 FAILED。
 *
 * <p><b>覆盖场景</b>：{@code task_record} 插入成功但 {@code @Async} 调度失败的边缘场景
 * （例如线程池满、任务被拒绝、JVM 异常等）。
 *
 * <p><b>与崩溃恢复的协作</b>：
 * <ul>
 *   <li>启动时崩溃恢复：处理"应用崩溃留下的 PENDING/RUNNING"</li>
 *   <li>运行期 PENDING 超时检查：处理"调度失败留下的 PENDING"</li>
 * </ul>
 *
 * <p><b>互斥键处理</b>：PENDING 任务被标记 FAILED 后，同步调用
 * {@link TaskMutexManager#removeByTaskId} 释放互斥键，避免互斥键被永久占用导致后续任务无法提交。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPendingTimeoutScheduler {

    private final TaskRecordMapper taskRecordMapper;
    private final TaskMutexManager taskMutexManager;
    private final TaskHandlerRegistry handlerRegistry;
    private final TaskCenterProperties properties;

    /**
     * 每 minute 检查 PENDING 超时任务（cron 由配置驱动，默认 {@code 0 * * * * ?}）。
     *
     * <p>使用 Spring 的 SpEL 从配置读取 cron 表达式，支持 {@code game-platform.task-center.pending-timeout.check-cron} 覆盖。
     */
    @Scheduled(cron = "${game-platform.task-center.pending-timeout.check-cron:0 * * * * ?}")
    public void checkPendingTimeout() {
        if (!properties.getPendingTimeout().isEnabled()) {
            return;
        }

        int timeoutMinutes = properties.getPendingTimeout().getTimeoutMinutes();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);

        List<TaskRecord> pendingTimeouts = taskRecordMapper.selectPendingTimeoutTasks(cutoff);
        if (pendingTimeouts.isEmpty()) {
            return;
        }

        log.warn("[TaskCenter] 检测到 {} 个 PENDING 超时任务（> {} 分钟）",
                pendingTimeouts.size(), timeoutMinutes);

        LocalDateTime now = LocalDateTime.now();
        String errorMessage = "任务提交后未在 " + timeoutMinutes + " 分钟内开始执行，可能因线程池满或应用异常";
        int processed = 0;
        for (TaskRecord task : pendingTimeouts) {
            try {
                int rows = taskRecordMapper.updateToFailed(
                        task.getId(), errorMessage, null, now, null, now);
                if (rows > 0) {
                    // 同步释放互斥键 + 清理 source 索引
                    taskMutexManager.removeByTaskId(task.getId());
                    handlerRegistry.removeTaskSourceIndex(task.getId());
                    processed++;
                    log.warn("[TaskCenter] PENDING 超时: taskId={}, type={}, createTime={}",
                            task.getId(), task.getTaskType(), task.getCreateTime());
                }
            } catch (Exception e) {
                log.error("[TaskCenter] PENDING 超时处理失败: taskId={}, err={}",
                        task.getId(), e.getMessage());
            }
        }

        if (processed > 0) {
            log.info("[TaskCenter] PENDING 超时检查完成，已处理 {} 个任务", processed);
        }
    }
}
