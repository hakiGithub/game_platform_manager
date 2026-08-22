package com.gameplatform.schedule;

import com.gameplatform.config.ScheduledTaskProperties;
import com.gameplatform.mapper.ScheduledTaskRunLogMapper;
import com.gameplatform.mapper.ScheduledTaskRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 过期触发记录清理调度器（ADR-0011 D2 保留策略）
 *
 * <p>每天凌晨 3:30 清理过期触发记录（默认保留 30 天，错开任务中心 3:00 清理）：
 * 先级联删除 {@code scheduled_task_run_log}，再物理删除 {@code scheduled_task_run}，
 * 避免孤儿日志。清理采用物理删除，防止表长期膨胀拖累分页查询。
 *
 * <p>计划定义（{@code scheduled_task}）不参与清理——只有用户删除或插件卸载才会移除。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskRunCleanupScheduler {

    /** 日志级联删除的分批大小（避免 IN 子句过长） */
    private static final int LOG_DELETE_BATCH_SIZE = 500;

    private final ScheduledTaskRunMapper runMapper;
    private final ScheduledTaskRunLogMapper runLogMapper;
    private final ScheduledTaskProperties properties;

    /**
     * 每天凌晨清理过期触发记录（cron 由配置驱动，默认 {@code 0 30 3 * * ?}）。
     */
    @Scheduled(cron = "${game-platform.scheduled-task.cleanup.cron:0 30 3 * * ?}")
    public void cleanupExpiredRuns() {
        if (!properties.getCleanup().isEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRunRetentionDays());

        // 1. 查询待清理 runId（级联删除日志用）
        List<String> runIds = runMapper.selectIdsBefore(cutoff);
        if (runIds.isEmpty()) {
            return;
        }

        // 2. 先删日志（分批）
        int logsDeleted = 0;
        for (int i = 0; i < runIds.size(); i += LOG_DELETE_BATCH_SIZE) {
            List<String> batch = runIds.subList(i, Math.min(i + LOG_DELETE_BATCH_SIZE, runIds.size()));
            try {
                logsDeleted += runLogMapper.deleteByRunIds(batch);
            } catch (Exception e) {
                log.warn("[Schedule] 清理触发日志失败（批次 {}）: {}", i / LOG_DELETE_BATCH_SIZE, e.getMessage());
            }
        }

        // 3. 再物理删除触发记录
        int runsDeleted = runMapper.physicalDeleteBefore(cutoff);

        log.info("[Schedule] 清理过期触发记录完成: {} 条记录, {} 条日志（保留 {} 天）",
                runsDeleted, logsDeleted, properties.getRunRetentionDays());
    }
}
