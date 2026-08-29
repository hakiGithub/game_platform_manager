package com.gameplatform.config;

import lombok.extern.slf4j.Slf4j;

/**
 * 数据库方言（ADR-0015）。
 *
 * <p>平台支持 SQLite / MySQL / PostgreSQL 三种数据库，方言由启动时
 * {@link DatabaseDialectResolver} 读取 {@code DatabaseMetaData.getDatabaseProductName()}
 * 自动判定，不引入额外配置开关。方言决定：
 * <ul>
 *   <li>建表 / 种子脚本：<code>db/schema-{key}.sql</code> 与 <code>db/data-{key}.sql</code></li>
 *   <li>插件扩展存储 DDL（{@link com.gameplatform.plugin.extension.DdlTemplate}）的索引语法</li>
 *   <li>SQLite 专属迁移体系（db/migration/）是否执行</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
public enum DatabaseDialect {

    SQLITE("sqlite", true),
    MYSQL("mysql", false),
    POSTGRESQL("postgresql", true);

    /** 脚本文件名后缀，如 schema-sqlite.sql */
    private final String scriptKey;

    /** 是否支持 CREATE INDEX IF NOT EXISTS（MySQL 不支持） */
    private final boolean supportsCreateIndexIfNotExists;

    DatabaseDialect(String scriptKey, boolean supportsCreateIndexIfNotExists) {
        this.scriptKey = scriptKey;
        this.supportsCreateIndexIfNotExists = supportsCreateIndexIfNotExists;
    }

    public String schemaLocation() {
        return "db/schema-" + scriptKey + ".sql";
    }

    public String dataLocation() {
        return "db/data-" + scriptKey + ".sql";
    }

    public boolean supportsCreateIndexIfNotExists() {
        return supportsCreateIndexIfNotExists;
    }

    /**
     * 按数据库产品名判定方言。
     * MariaDB 的产品名是 "MariaDB" 而非 "MySQL"，归入 MySQL 方言
     * （平台的 MySQL 方言脚本刻意只使用 MariaDB 也兼容的语法）。
     *
     * @param productName DatabaseMetaData.getDatabaseProductName() 返回值
     * @return 对应方言
     */
    public static DatabaseDialect fromProductName(String productName) {
        if (productName == null || productName.isBlank()) {
            throw new IllegalStateException("无法从连接元数据读取数据库产品名");
        }
        String normalized = productName.toLowerCase();
        if (normalized.contains("sqlite")) {
            return SQLITE;
        }
        if (normalized.contains("mariadb") || normalized.contains("mysql")) {
            return MYSQL;
        }
        if (normalized.contains("postgres")) {
            return POSTGRESQL;
        }
        throw new IllegalStateException("不支持的数据库类型: " + productName);
    }
}
