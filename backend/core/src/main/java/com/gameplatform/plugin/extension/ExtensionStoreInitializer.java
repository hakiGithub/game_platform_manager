package com.gameplatform.plugin.extension;

import com.gameplatform.config.DatabaseDialectResolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时创建全局 SHARED 表 {@code extensions}。
 * <p>
 * 必须在任何插件加载之前完成，确保 SHARED 策略的 Extension 资源有表可写。
 * DDL 按 {@link DatabaseDialectResolver} 检测到的方言生成（ADR-0015）。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtensionStoreInitializer {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialectResolver dialectResolver;

    @PostConstruct
    public void init() {
        String ddl = DdlTemplate.generate("extensions", dialectResolver.resolve());
        // 模板可能含多条语句（SQLite/PG 附带 CREATE INDEX），逐条执行保证方言兼容
        for (String statement : ddl.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
            }
        }
        log.info("[ExtensionStore] 全局 extensions 表已就绪");
    }
}
