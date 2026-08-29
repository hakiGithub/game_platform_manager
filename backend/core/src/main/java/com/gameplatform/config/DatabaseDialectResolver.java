package com.gameplatform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库方言解析器（ADR-0015）。
 *
 * <p>启动阶段从 {@link DataSource} 的连接元数据判定方言并缓存，
 * 全进程只做一次检测。DatabaseInitializer、SchemaMigrationRunner
 * 与插件扩展存储初始化统一经此获取方言。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseDialectResolver {

    private final DataSource dataSource;

    private volatile DatabaseDialect cached;

    /**
     * 获取当前数据库方言（首次调用时检测，之后复用缓存）。
     */
    public DatabaseDialect resolve() {
        DatabaseDialect dialect = cached;
        if (dialect != null) {
            return dialect;
        }
        synchronized (this) {
            if (cached == null) {
                cached = detect();
            }
            return cached;
        }
    }

    private DatabaseDialect detect() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            DatabaseDialect dialect = DatabaseDialect.fromProductName(productName);
            log.info("检测到数据库方言: {} (产品名: {})", dialect, productName);
            return dialect;
        } catch (SQLException e) {
            throw new IllegalStateException("数据库方言检测失败", e);
        }
    }
}
