package com.gameplatform.task;

import com.gameplatform.config.InstanceSyncProperties;
import com.gameplatform.service.sync.InstanceSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 实例状态同步启动钩子
 *
 * <p>应用启动后延迟 {@link InstanceSyncProperties#getStartupSyncDelayMs()} 毫秒，
 * 异步执行一次全量实例状态同步，将主机上实际运行的游戏服务器状态对账到平台库。
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用 {@link Async} + taskExecutor 避免阻塞主线程启动</li>
 *   <li>延迟执行确保 SSH 客户端、Docker 客户端等组件已就绪</li>
 *   <li>异常隔离：同步失败不影响应用启动</li>
 *   <li>禁用配置 (game-platform.instance-sync.enabled=false) 时跳过</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
@Order(100)
public class InstanceSyncStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstanceSyncStartupRunner.class);
    private static final String LOG_PREFIX = "[InstanceSync]";

    private final InstanceSyncService syncService;
    private final InstanceSyncProperties properties;

    public InstanceSyncStartupRunner(InstanceSyncService syncService, InstanceSyncProperties properties) {
        this.syncService = syncService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("{} 启动同步已禁用 (game-platform.instance-sync.enabled=false)", LOG_PREFIX);
            return;
        }

        long delayMs = properties.getStartupSyncDelayMs();
        log.info("{} 应用启动，将在 {}ms 后异步执行实例状态同步", LOG_PREFIX, delayMs);

        triggerAsyncSync(delayMs);
    }

    /**
     * 异步延迟执行同步，独立线程不阻塞启动
     * 包级可见以便测试
     */
    @Async("taskExecutor")
    public void triggerAsyncSync(long delayMs) {
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            log.info("{} 启动同步开始执行", LOG_PREFIX);
            InstanceSyncService.SyncSummary summary = syncService.syncAll();
            log.info("{} 启动同步结束: {}", LOG_PREFIX, summary);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} 启动同步被中断", LOG_PREFIX);
        } catch (Exception e) {
            log.error("{} 启动同步异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }
}
