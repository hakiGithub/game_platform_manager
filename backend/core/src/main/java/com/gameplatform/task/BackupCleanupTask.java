package com.gameplatform.task;

import com.gameplatform.config.GamePlatformConfig;
import com.gameplatform.service.BackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 备份清理定时任务
 * 定期清理过期的备份文件
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupCleanupTask {

    private final BackupService backupService;
    private final GamePlatformConfig gamePlatformConfig;

    /**
     * 每天凌晨2点执行备份清理
     * 清理超过保留天数的备份文件
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredBackups() {
        try {
            // 检查是否启用自动清理
            if (!Boolean.TRUE.equals(gamePlatformConfig.getBackup().getAutoCleanup())) {
                log.debug("自动清理已禁用,跳过备份清理任务");
                return;
            }

            int retentionDays = gamePlatformConfig.getBackup().getRetentionDays();
            log.info("开始执行备份清理任务,保留天数: {}", retentionDays);

            int count = backupService.cleanupExpiredBackups(retentionDays);

            log.info("备份清理任务完成,共清理 {} 个过期备份", count);
        } catch (Exception e) {
            log.error("备份清理任务执行失败", e);
        }
    }

    /**
     * 每小时检查一次备份状态
     * 清理长时间处于"备份中"状态的异常备份记录
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanupStaleBackups() {
        try {
            log.debug("检查异常备份记录...");
            // 这里可以实现清理长时间处于备份中状态的记录
            // 例如: 超过2小时仍处于备份中状态的记录标记为失败
        } catch (Exception e) {
            log.error("检查异常备份记录失败", e);
        }
    }

}
