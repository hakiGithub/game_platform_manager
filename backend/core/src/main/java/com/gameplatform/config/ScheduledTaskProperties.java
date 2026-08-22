package com.gameplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务管理配置项（ADR-0011）
 *
 * <p>对应配置前缀 {@code game-platform.scheduled-task}：
 * <pre>
 * game-platform:
 *   scheduled-task:
 *     enabled: true
 *     pool-size: 4
 *     run-retention-days: 30
 *     cleanup:
 *       enabled: true
 *       cron: "0 30 3 * * ?"
 * </pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "game-platform.scheduled-task")
public class ScheduledTaskProperties {

    /** 总开关（false 时不注册任何 cron 触发，手动触发仍可用） */
    private boolean enabled = true;

    /** 执行线程池大小（run 执行专用，与任务中心线程池隔离） */
    private int poolSize = 4;

    /** 触发记录保留天数 */
    private int runRetentionDays = 30;

    /** 清理配置 */
    private Cleanup cleanup = new Cleanup();

    @Data
    public static class Cleanup {
        /** 是否启用过期触发记录清理（默认开启） */
        private boolean enabled = true;

        /** 清理 cron 表达式（默认凌晨 3:30，错开任务中心 3:00 清理） */
        private String cron = "0 30 3 * * ?";
    }
}
