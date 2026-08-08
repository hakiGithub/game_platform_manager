package com.gameplatform.plugin.extension;

import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

/**
 * 基于 Hutool 雪花算法的 {@link ExtensionIdGenerator} 默认实现。
 * <p>
 * 单机部署使用默认 workerId=0/datacenterId=0；多机部署可改为构造 {@code new Snowflake(workerId, datacenterId)}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class SnowflakeIdGenerator implements ExtensionIdGenerator {

    @Override
    public String nextId() {
        return IdUtil.getSnowflakeNextIdStr();
    }
}
