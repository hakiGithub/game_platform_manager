package com.gameplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * API 令牌配置（ADR-0029）。
 *
 * <p>{@code game-platform.api-token.*} 命名空间，字段默认值自负（与插件配置同规矩），
 * 无必填项、不新增配置即可运行。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "game-platform.api-token")
public class ApiTokenProperties {

    /** last_used_at 节流窗口（秒）：距上次记录不足该时长则不写库。 */
    private long lastUsedThrottleSeconds = 60;

}
