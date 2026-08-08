package com.gameplatform.task;

import com.gameplatform.config.TaskCenterProperties;
import com.gameplatform.entity.TaskRecord;
import com.gameplatform.enums.TaskStatus;
import com.gameplatform.mapper.TaskLogMapper;
import com.gameplatform.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 过期任务清理调度器（ADR-010 保留策略）
 *
 * <p>每天凌晨 3 点清理过期终态任务：
 * <ul>
 *   <li>COMPLETED / CANCELLED：保留 {@code completed-retention-days} 天（默认 30 天）</li>
 *   <li>FAILED：保留 {@code failed-retention-days} 天（默认 90 天）</li>
 * </ul>
 *
 * <p>清理时级联删除 {@code task_log} 表的关联记录（先日志、再任务记录），避免孤儿日志。
 *
 * <p><b>物理删除 vs 软删除</b>：清理采用物理删除（{@link TaskRecordMapper#physicalDeleteByStatusAndTime}），
 * 防止 task_record 表长期膨胀拖累查询性能。task_record 表的 {@code is_deleted} 字段
 * 用于普通用户操作（删除单个任务），定时清理直接物理删除（含已逻辑删除的记录）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskCleanupScheduler {

    private final TaskRecordMapper taskRecordMapper;
    private final TaskLogMapper taskLogMapper;
    private final TaskHandlerRegistry handlerRegistry;
    private final TaskCenterProperties properties;

    /**
     * 每天凌晨 3 点清理过期任务（cron 由配置驱动，默认 {@code 0 0 3 * * ?}）。
     *
     * <p>使用 Spring 的 SpEL 从配置读取 cron 表达式，支持 {@code game-platform.task-center.cleanup.cron} 覆盖。
     */
    @Scheduled(cron = "${game-platform.task-center.cleanup.cron:0 0 3 * * ?}")
    public void cleanupExpiredTasks() {
        if (!properties.getCleanup().isEnabled()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int completedRetentionDays = properties.getCleanup().getCompletedRetentionDays();
        int failedRetentionDays = properties.getCleanup().getFailedRetentionDays();

        int deletedCompleted = cleanupTerminalTasks(
                List.of(TaskStatus.COMPLETED.name(), TaskStatus.CANCELLED.name()),
                now.minusDays(completedRetentionDays));
        int deletedFailed = cleanupTerminalTasks(
                List.of(TaskStatus.FAILED.name()),
                now.minusDays(failedRetentionDays));

        if (deletedCompleted + deletedFailed > 0) {
            log.info("[TaskCenter] 清理过期任务完成: 终态 {} 条 (保留 {} 天), FAILED {} 条 (保留 {} 天)",
                    deletedCompleted, completedRetentionDays,
                    deletedFailed, failedRetentionDays);
        }
    }

    /**
     * 清理指定状态 + 时间阈值之前的任务
     *
     * <p>流程：先查 ID 列表 → 级联删除 task_log → 物理删除 task_record → 清理 source 索引。
     *
     * @param statuses 任务状态列表
     * @param cutoff   创建时间阈值（早于此值的任务将被清理）
     * @return 已删除的任务记录条数
     */
    private int cleanupTerminalTasks(List<String> statuses, LocalDateTime cutoff) {
        // 1. 先查询匹配的 taskId 列表（用于级联删除日志 + 清理 source 索引）
        List<TaskRecord> tasks = taskRecordMapper.selectIdsByStatusAndTime(statuses, cutoff);
        if (tasks.isEmpty()) {
            return 0;
        }

        // 2. 先删除 task_log（按 taskId 逐条物理删除，避免子查询性能问题）
        int logsDeleted = 0;
        for (TaskRecord task : tasks) {
            try {
                logsDeleted += taskLogMapper.deleteByTaskId(task.getId());
            } catch (Exception e) {
                log.warn("[TaskCenter] 清理任务日志失败: taskId={}, err={}",
                        task.getId(), e.getMessage());
            }
        }

        // 3. 物理删除 task_record（按 status + create_time 批量删除）
        int recordsDeleted = taskRecordMapper.physicalDeleteByStatusAndTime(statuses, cutoff);

        // 4. 清理 TaskHandlerRegistry 中的 source 索引（防内存泄漏）
        for (TaskRecord task : tasks) {
            handlerRegistry.removeTaskSourceIndex(task.getId());
        }

        log.info("[TaskCenter] 清理 {} 状态任务: {} 条记录, {} 条日志",
                statuses, recordsDeleted, logsDeleted);
        return recordsDeleted;
    }
}
