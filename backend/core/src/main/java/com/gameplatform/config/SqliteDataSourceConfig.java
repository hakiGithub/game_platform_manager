package com.gameplatform.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * SQLite 文件库并发配置。
 *
 * <p>Hikari 的 connection-timeout 只限制等待连接池中的连接，无法避免
 * SQLite 写锁立即返回 SQLITE_BUSY。WAL 允许读写并行，busy_timeout 则让
 * 写事务在短暂争用时等待，而不是直接以 500 失败。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SqliteDataSourceConfig {

    private static final String SQLITE_DRIVER = "org.sqlite.JDBC";
    private static final String SQLITE_BUSY_TIMEOUT_MS = "30000";

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        if (!isSqlite(properties)) {
            return dataSource;
        }

        Properties dataSourceProperties = new Properties();
        dataSourceProperties.setProperty("busy_timeout", SQLITE_BUSY_TIMEOUT_MS);
        dataSource.setDataSourceProperties(dataSourceProperties);
        dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
        return dataSource;
    }

    private boolean isSqlite(DataSourceProperties properties) {
        return StringUtils.hasText(properties.getUrl())
                && properties.getUrl().startsWith("jdbc:sqlite:")
                && (!StringUtils.hasText(properties.getDriverClassName())
                || SQLITE_DRIVER.equals(properties.getDriverClassName()));
    }
}
