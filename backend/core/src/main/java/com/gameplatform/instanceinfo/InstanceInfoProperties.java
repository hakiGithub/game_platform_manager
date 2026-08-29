package com.gameplatform.instanceinfo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实例动态信息查询配置（ADR-0017）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "instance-info")
public class InstanceInfoProperties {

    /** 实例维度缓存 TTL（秒） */
    private int cacheTtlSeconds = 15;

    /** 列表场景整体查询预算（毫秒），超预算的实例本次降级 */
    private long queryBudgetMillis = 3000;

    /** 单实例查询超时（毫秒） */
    private long perQueryTimeoutMillis = 5000;

    private Pool pool = new Pool();

    @Data
    public static class Pool {
        /** 常驻线程数 */
        private int coreSize = 4;
        /** 最大线程数 */
        private int maxSize = 8;
    }
}
