package com.gameplatform.plugin.extension;

/**
 * 统一宽表 DDL 模板生成器。
 * <p>
 * 所有宽表（无论哪层策略）用同一份模板，只是表名不同。
 * 本期仅实现 SQLite 版；多数据库支持只需切换模板。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class DdlTemplate {

    private DdlTemplate() {
    }

    /**
     * 生成建表 SQL（含基础索引）。
     *
     * @param tableName 表名（已 sanitize）
     * @return 可执行的 SQL 字符串
     */
    public static String generate(String tableName) {
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
     * 生成 DROP TABLE SQL。
     *
     * @param tableName 表名（已 sanitize）
     * @return 可执行的 SQL 字符串
     */
    public static String drop(String tableName) {
        return "DROP TABLE IF EXISTS " + tableName;
    }
}
