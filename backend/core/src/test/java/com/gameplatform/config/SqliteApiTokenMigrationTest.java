package com.gameplatform.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-8: SQLite 存量库 api_token 迁移测试（ADR-0029，仿 {@link SqliteDialectInitializationTest} 风格）。
 *
 * <p>用临时文件 SQLite 库模拟「老库升级」：预置老结构后连跑
 * {@link SchemaMigrationRunner#afterPropertiesSet()} 两次，验证建表、列齐、
 * 唯一索引生效、二次执行幂等。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class SqliteApiTokenMigrationTest {

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "id", "name", "token_prefix", "token_hash", "scope", "user_id",
            "expires_at", "revoked", "revoked_at", "last_used_at",
            "create_time", "update_time", "is_deleted", "remark");

    @TempDir
    static Path tempDir;

    private static SingleConnectionDataSource dataSource;

    @AfterAll
    static void releaseConnection() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("老库升级：V1.11 建表、列齐、唯一哈希生效、二次执行幂等")
    void legacyDbMigrationIdempotent() {
        String url = "jdbc:sqlite:" + tempDir.resolve("ut-api-token.sqlite");
        dataSource = new SingleConnectionDataSource(url, true);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // 预置老库结构（只有用户表，无 api_token）
        jdbcTemplate.execute("CREATE TABLE sys_user (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "username VARCHAR(50) NOT NULL, " +
                "password_hash VARCHAR(128) NOT NULL)");

        DatabaseDialectResolver dialectResolver = new DatabaseDialectResolver(dataSource);
        assertEquals(DatabaseDialect.SQLITE, dialectResolver.resolve(), "应判定为 SQLite 方言");

        SchemaMigrationRunner runner = new SchemaMigrationRunner(dialectResolver, jdbcTemplate);

        // 第一次：补建 api_token
        runner.afterPropertiesSet();
        assertTrue(tableExists(jdbcTemplate, "api_token"), "V1.11 应建出 api_token 表");

        // 列齐全
        Set<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(api_token)").stream()
                .map(col -> String.valueOf(col.get("name")))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(EXPECTED_COLUMNS, columns, "api_token 列应与设计一致");

        // 唯一索引生效：重复 token_hash 抛约束冲突
        // （spring-jdbc 对 SQLite 无错误码映射，原始 SQLiteException 经 UncategorizedSQLException 包装）
        jdbcTemplate.update("INSERT INTO api_token (name, token_prefix, token_hash, scope, user_id, revoked) " +
                "VALUES ('a', 'gpm_prefix1234', 'same-hash-value', 'read', 1, 0)");
        Exception dup = assertThrows(Exception.class, () ->
                jdbcTemplate.update("INSERT INTO api_token (name, token_prefix, token_hash, scope, user_id, revoked) " +
                        "VALUES ('b', 'gpm_prefix1234', 'same-hash-value', 'read', 1, 0)"),
                "uk_api_token_hash 唯一索引应生效");
        assertTrue(String.valueOf(dup).contains("UNIQUE constraint failed"),
                "应为唯一约束冲突: " + dup);

        // 第二次：不报错、不重复建
        runner.afterPropertiesSet();
        Long tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='api_token'", Long.class);
        assertEquals(1L, tableCount, "二次执行不得重复建表");
    }

    private static boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", tableName);
        return !rows.isEmpty();
    }

}
