package com.gameplatform.plugin.extension;

import com.gameplatform.config.DatabaseDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DdlTemplate} 单元测试。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@DisplayName("DdlTemplate SQL 生成测试")
class DdlTemplateTest {

    @Test
    @DisplayName("SQLite 方言：generate 生成包含 id 主键与 name 复合唯一约束的建表 SQL")
    void generateSqlite_containsIdPrimaryKey() {
        String sql = DdlTemplate.generate("extensions", DatabaseDialect.SQLITE);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS extensions"),
                "应包含建表语句");
        assertTrue(sql.contains("PRIMARY KEY (id)"),
                "应使用 id 作为主键");
        assertTrue(sql.contains("UNIQUE (name, group_name, kind)"),
                "应保留 (name, group_name, kind) 唯一约束");
    }

    @Test
    @DisplayName("SQLite 方言：generate 包含所有必需列")
    void generateSqlite_containsAllRequiredColumns() {
        String sql = DdlTemplate.generate("extensions", DatabaseDialect.SQLITE);
        assertTrue(sql.contains("id VARCHAR(20) NOT NULL"), "应包含 id 列");
        assertTrue(sql.contains("name VARCHAR(64) NOT NULL"), "应包含 name 列");
        assertTrue(sql.contains("group_name VARCHAR(128) NOT NULL"), "应包含 group_name 列");
        assertTrue(sql.contains("kind VARCHAR(128) NOT NULL"), "应包含 kind 列");
        assertTrue(sql.contains("version INT DEFAULT 1"), "应包含 version 列");
        assertTrue(sql.contains("metadata TEXT NOT NULL"), "应包含 metadata 列");
        assertTrue(sql.contains("spec TEXT NOT NULL"), "应包含 spec 列");
        assertTrue(sql.contains("status VARCHAR(32) DEFAULT 'ACTIVE'"), "应包含 status 列");
        assertTrue(sql.contains("creation_timestamp BIGINT"), "应包含 creation_timestamp 列");
        assertTrue(sql.contains("update_timestamp BIGINT"), "应包含 update_timestamp 列");
    }

    @Test
    @DisplayName("SQLite/PG 方言：generate 包含 3 个 CREATE INDEX IF NOT EXISTS 语句")
    void generateAnsi_containsThreeIndexes() {
        for (DatabaseDialect dialect : new DatabaseDialect[]{
                DatabaseDialect.SQLITE, DatabaseDialect.POSTGRESQL}) {
            String sql = DdlTemplate.generate("extensions", dialect);
            assertTrue(sql.contains("idx_extensions_group_kind"), dialect + " 应含 group_kind 索引");
            assertTrue(sql.contains("idx_extensions_status"), dialect + " 应含 status 索引");
            assertTrue(sql.contains("idx_extensions_creation"), dialect + " 应含 creation 索引");

            String[] statements = sql.split(";");
            long actualIndexCount = java.util.Arrays.stream(statements)
                    .filter(s -> s.contains("CREATE INDEX IF NOT EXISTS")).count();
            assertEquals(3, actualIndexCount, dialect + " 应有 3 个 CREATE INDEX IF NOT EXISTS 语句");
        }
    }

    @Test
    @DisplayName("MySQL 方言：索引内联且为单条语句（规避 CREATE INDEX IF NOT EXISTS）")
    void generateMysql_inlinesIndexesAsSingleStatement() {
        String sql = DdlTemplate.generate("extensions", DatabaseDialect.MYSQL);
        assertFalse(sql.contains("CREATE INDEX"), "MySQL 方言不应生成独立 CREATE INDEX 语句");
        assertFalse(sql.contains(";"), "MySQL 方言应为单条语句（避免多语句执行限制）");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS extensions"), "应包含建表语句");
        assertTrue(sql.contains("PRIMARY KEY (id)"), "应保留 id 主键");
        assertTrue(sql.contains("UNIQUE KEY uk_extensions_group_kind (name, group_name, kind)"),
                "唯一约束应内联为 UNIQUE KEY");
        assertTrue(sql.contains("KEY idx_extensions_group_kind (group_name, kind)"),
                "group_kind 索引应内联");
        assertTrue(sql.contains("KEY idx_extensions_status (status)"),
                "status 索引应内联");
        assertTrue(sql.contains("KEY idx_extensions_creation (creation_timestamp)"),
                "creation 索引应内联");
        assertTrue(sql.toUpperCase().contains("ENGINE=INNODB"), "应显式指定 InnoDB 引擎");
        assertTrue(sql.toUpperCase().contains("CHARSET=UTF8MB4"), "应显式指定 utf8mb4 字符集");
    }

    @Test
    @DisplayName("generate 对不同表名生成对应索引名")
    void generate_tableSpecificIndexNames() {
        String sql = DdlTemplate.generate("ext_plugin_l4d2_admin", DatabaseDialect.SQLITE);
        assertTrue(sql.contains("idx_ext_plugin_l4d2_admin_group_kind"));
        assertTrue(sql.contains("idx_ext_plugin_l4d2_admin_status"));
        assertTrue(sql.contains("idx_ext_plugin_l4d2_admin_creation"));

        String mysqlSql = DdlTemplate.generate("ext_plugin_l4d2_admin", DatabaseDialect.MYSQL);
        assertTrue(mysqlSql.contains("idx_ext_plugin_l4d2_admin_group_kind"));
        assertTrue(mysqlSql.contains("uk_ext_plugin_l4d2_admin_group_kind"));
    }

    @Test
    @DisplayName("drop 生成 DROP TABLE IF EXISTS 语句")
    void drop_generatesDropStatement() {
        String sql = DdlTemplate.drop("ext_x");
        assertEquals("DROP TABLE IF EXISTS ext_x", sql);
    }

    @Test
    @DisplayName("drop 对不同表名生成对应 SQL")
    void drop_tableSpecific() {
        assertEquals("DROP TABLE IF EXISTS extensions", DdlTemplate.drop("extensions"));
        assertEquals("DROP TABLE IF EXISTS ext_plugin_l4d2_admin",
                DdlTemplate.drop("ext_plugin_l4d2_admin"));
    }
}
