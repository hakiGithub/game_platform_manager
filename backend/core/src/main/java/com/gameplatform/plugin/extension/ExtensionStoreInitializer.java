package com.gameplatform.plugin.extension;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时创建全局 SHARED 表 {@code extensions}。
 * <p>
 * 必须在任何插件加载之前完成，确保 SHARED 策略的 Extension 资源有表可写。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtensionStoreInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(DdlTemplate.generate("extensions"));
        log.info("[ExtensionStore] 全局 extensions 表已就绪");
    }
}
