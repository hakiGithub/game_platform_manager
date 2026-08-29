package com.gameplatform.rcon;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RCON 主应用配置（ADR-0016）。
 * <p>
 * 传输层统一配置，前缀 {@code rcon.*}。默认值自负（对齐原 plugin-l4d2 池参数），
 * application.yml 可覆盖。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "rcon")
public class RconProperties {

    /** Socket 读超时（毫秒），即单条命令的响应等待上限 */
    private int readTimeoutMs = 5000;

    /** TCP 连接超时（毫秒） */
    private int connectTimeoutMs = 5000;

    /** 认证失败重试次数 */
    private int authRetryCount = 3;

    private Pool pool = new Pool();

    @Data
    public static class Pool {
        /** 空闲超时（秒），超过后连接关闭回收 */
        private int idleTimeoutSeconds = 300;
        /** 最大寿命（秒），防止长期持有导致服务端断开 */
        private int maxAgeSeconds = 1800;
        /** 清理扫描间隔（秒） */
        private int cleanIntervalSeconds = 60;
        /** 借用等待超时（秒） */
        private int borrowTimeoutSeconds = 3;
        /** 缓存开关，false 时每次新建连接 */
        private boolean enabled = true;
    }
}
