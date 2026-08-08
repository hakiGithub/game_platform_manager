package com.gameplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 任务中心配置项（ADR-007 / ADR-017）
 *
 * <p>对应配置前缀 {@code game-platform.task-center}：
 * <pre>
 * game-platform:
 *   task-center:
 *     crash-recovery:
 *       enabled: true
 *     pending-timeout:
 *       enabled: true
 *       timeout-minutes: 5
 *       check-cron: "0 * * * * ?"
 *     cleanup:
 *       enabled: true
 *       cron: "0 0 3 * * ?"
 *       completed-retention-days: 30
 *       failed-retention-days: 90
 * </pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "game-platform.task-center")
public class TaskCenterProperties {

    /** 崩溃恢复配置 */
    private CrashRecovery crashRecovery = new CrashRecovery();

    /** PENDING 超时检查配置 */
    private PendingTimeout pendingTimeout = new PendingTimeout();

    /** 过期任务清理配置 */
    private Cleanup cleanup = new Cleanup();

    @Data
    public static class CrashRecovery {
        /** 是否启用启动时崩溃恢复（默认开启） */
        private boolean enabled = true;
    }

    @Data
    public static class PendingTimeout {
        /** 是否启用 PENDING 超时检查（默认开启） */
        private boolean enabled = true;

        /** PENDING 超时阈值（分钟） */
        private int timeoutMinutes = 5;

        /** 检查 cron 表达式（默认每分钟） */
        private String checkCron = "0 * * * * ?";
    }

    @Data
    public static class Cleanup {
        /** 是否启用过期任务清理（默认开启） */
        private boolean enabled = true;

        /** 清理 cron 表达式（默认每天凌晨 3 点） */
        private String cron = "0 0 3 * * ?";

        /** COMPLETED/CANCELLED 任务保留天数 */
        private int completedRetentionDays = 30;

        /** FAILED 任务保留天数 */
        private int failedRetentionDays = 90;
    }
}
