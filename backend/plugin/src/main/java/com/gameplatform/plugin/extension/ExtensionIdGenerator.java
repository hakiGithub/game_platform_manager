package com.gameplatform.plugin.extension;

/**
 * 扩展资源 ID 生成器接口。
 * <p>
 * 默认实现 {@code SnowflakeIdGenerator} 使用 Hutool 雪花算法。
 * 可替换为其他策略（如 UUID、数据库序列）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ExtensionIdGenerator {

    /**
     * 生成全局唯一 String 类型 ID。
     *
     * @return ID 字符串
     */
    String nextId();
}
