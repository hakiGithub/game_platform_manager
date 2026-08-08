package com.gameplatform.plugin.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DdlTemplate} 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("DdlTemplate SQL 生成测试")
class DdlTemplateTest {

    @Test
    @DisplayName("generate 生成包含 id 主键与 name 复合唯一约束的建表 SQL")
    void generate_containsIdPrimaryKey() {
        String sql = DdlTemplate.generate("extensions");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS extensions"),
                "应包含建表语句");
        assertTrue(sql.contains("PRIMARY KEY (id)"),
                "应使用 id 作为主键");
        assertTrue(sql.contains("UNIQUE (name, group_name, kind)"),
                "应保留 (name, group_name, kind) 唯一约束");
    }

    @Test
    @DisplayName("generate 包含所有必需列")
    void generate_containsAllRequiredColumns() {
        String sql = DdlTemplate.generate("extensions");
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
    @DisplayName("generate 包含 3 个基础索引")
    void generate_containsThreeIndexes() {
        String sql = DdlTemplate.generate("extensions");
        // 索引名应包含表名前缀
        assertTrue(sql.contains("idx_extensions_group_kind"), "应含 group_kind 索引");
        assertTrue(sql.contains("idx_extensions_status"), "应含 status 索引");
        assertTrue(sql.contains("idx_extensions_creation"), "应含 creation 索引");

        // 计数 CREATE INDEX 出现次数
        long indexCount = sql.lines().filter(s -> s.contains("CREATE INDEX")).count();
        // 由于是单行 SQL，按分号拆分
        String[] statements = sql.split(";");
        long actualIndexCount = java.util.Arrays.stream(statements)
                .filter(s -> s.contains("CREATE INDEX")).count();
        assertEquals(3, actualIndexCount, "应有 3 个 CREATE INDEX 语句");
    }

    @Test
    @DisplayName("generate 对不同表名生成对应索引名")
    void generate_tableSpecificIndexNames() {
        String sql = DdlTemplate.generate("ext_plugin_l4d2_admin");
        assertTrue(sql.contains("idx_ext_plugin_l4d2_admin_group_kind"));
        assertTrue(sql.contains("idx_ext_plugin_l4d2_admin_status"));
        assertTrue(sql.contains("idx_ext_plugin_l4d2_admin_creation"));
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
