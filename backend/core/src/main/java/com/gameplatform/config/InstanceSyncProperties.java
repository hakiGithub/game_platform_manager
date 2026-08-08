package com.gameplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实例状态同步配置项
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
@ConfigurationProperties(prefix = "game-platform.instance-sync")
public class InstanceSyncProperties {

    /**
     * 是否启用同步
     */
    private boolean enabled = true;

    /**
     * 启动时同步延迟（毫秒）
     */
    private long startupSyncDelayMs = 10000L;

    /**
     * 同步日志级别
     */
    private String logLevel = "INFO";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getStartupSyncDelayMs() {
        return startupSyncDelayMs;
    }

    public void setStartupSyncDelayMs(long startupSyncDelayMs) {
        this.startupSyncDelayMs = startupSyncDelayMs;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }
}
