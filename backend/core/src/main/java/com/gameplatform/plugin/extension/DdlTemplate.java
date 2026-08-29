package com.gameplatform.plugin.extension;

import com.gameplatform.config.DatabaseDialect;

/**
 * 统一宽表 DDL 模板生成器。
 * <p>
 * 所有宽表（无论哪层策略）用同一份模板，只是表名不同。
 * 按数据库方言生成（ADR-0015）：
 * <ul>
 *   <li>SQLite / PostgreSQL：索引用 {@code CREATE INDEX IF NOT EXISTS}（两者均支持）</li>
 *   <li>MySQL：不支持 {@code CREATE INDEX IF NOT EXISTS}，索引内联在
 *       CREATE TABLE 的 KEY 子句中，随表的 IF NOT EXISTS 天然幂等</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
public final class DdlTemplate {

    private DdlTemplate() {
    }

    /**
     * 生成建表 SQL（含基础索引）。
     *
     * @param tableName 表名（已 sanitize）
     * @param dialect   数据库方言
     * @return 可执行的 SQL 字符串
     */
    public static String generate(String tableName, DatabaseDialect dialect) {
        if (dialect == DatabaseDialect.MYSQL) {
            return generateMysql(tableName);
        }
        return generateAnsi(tableName);
    }

    /**
     * SQLite / PostgreSQL 版：CREATE INDEX IF NOT EXISTS 均受支持。
     */
    private static String generateAnsi(String tableName) {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "id VARCHAR(20) NOT NULL, "
                + "name VARCHAR(64) NOT NULL, "
                + "group_name VARCHAR(128) NOT NULL, "
                + "kind VARCHAR(128) NOT NULL, "
                + "version INT DEFAULT 1, "
                + "metadata TEXT NOT NULL, "
                + "spec TEXT NOT NULL, "
                + "status VARCHAR(32) DEFAULT 'ACTIVE', "
                + "creation_timestamp BIGINT, "
                + "update_timestamp BIGINT, "
                + "PRIMARY KEY (id), "
                + "UNIQUE (name, group_name, kind)"
                + ");"
                + "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_group_kind ON " + tableName + "(group_name, kind);"
                + "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_status ON " + tableName + "(status);"
                + "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_creation ON " + tableName + "(creation_timestamp);";
    }

    /**
     * MySQL 版：索引内联（KEY 子句），避免不支持的 CREATE INDEX IF NOT EXISTS。
     * 注意 jdbcTemplate.execute() 单次只能执行一条语句，因此不拼接索引语句。
     */
    private static String generateMysql(String tableName) {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "id VARCHAR(20) NOT NULL, "
                + "name VARCHAR(64) NOT NULL, "
                + "group_name VARCHAR(128) NOT NULL, "
                + "kind VARCHAR(128) NOT NULL, "
                + "version INT DEFAULT 1, "
                + "metadata TEXT NOT NULL, "
                + "spec TEXT NOT NULL, "
                + "status VARCHAR(32) DEFAULT 'ACTIVE', "
                + "creation_timestamp BIGINT, "
                + "update_timestamp BIGINT, "
                + "PRIMARY KEY (id), "
                + "UNIQUE KEY uk_" + tableName + "_group_kind (name, group_name, kind), "
                + "KEY idx_" + tableName + "_group_kind (group_name, kind), "
                + "KEY idx_" + tableName + "_status (status), "
                + "KEY idx_" + tableName + "_creation (creation_timestamp)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";
    }

    /**
     * 生成 DROP TABLE SQL。
     *
     * @param tableName 表名（已 sanitize）
     * @return 可执行的 SQL 字符串
     */
    public static String drop(String tableName) {
        return "DROP TABLE IF EXISTS " + tableName;
    }
}
