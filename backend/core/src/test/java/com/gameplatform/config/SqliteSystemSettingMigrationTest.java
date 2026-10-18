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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 存量库系统设置表迁移测试（backlog #03）。
 *
 * <p>模拟老库只有核心用户表、没有 sys_setting 的升级路径，验证
 * {@link SchemaMigrationRunner#afterPropertiesSet()} 会补建设置表，且重复执行幂等。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class SqliteSystemSettingMigrationTest {

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
    @DisplayName("存量 SQLite 库：V1.9 自动补建 sys_setting 且二次执行幂等")
    void legacyDbCreatesSystemSettingTable() {
        String url = "jdbc:sqlite:" + tempDir.resolve("ut-system-setting.sqlite");
        dataSource = new SingleConnectionDataSource(url, true);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE TABLE sys_user (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "username VARCHAR(50) NOT NULL, " +
                "password_hash VARCHAR(128) NOT NULL)");

        DatabaseDialectResolver dialectResolver = new DatabaseDialectResolver(dataSource);
        assertEquals(DatabaseDialect.SQLITE, dialectResolver.resolve(), "应判定为 SQLite 方言");

        SchemaMigrationRunner runner = new SchemaMigrationRunner(dialectResolver, jdbcTemplate);
        runner.afterPropertiesSet();

        assertTrue(tableExists(jdbcTemplate, "sys_setting"), "V1.9 应补建 sys_setting 表");
        List<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(sys_setting)").stream()
                .map(column -> String.valueOf(column.get("name")))
                .sorted()
                .toList();
        assertEquals(List.of("setting_group", "setting_value"), columns,
                "sys_setting 列应与 V1.9 设计一致");

        jdbcTemplate.update("INSERT INTO sys_setting (setting_group, setting_value) VALUES (?, ?)",
                "platform", "{\"platformName\":\"E2E\"}");
        runner.afterPropertiesSet();

        Long tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sys_setting'",
                Long.class);
        assertEquals(1L, tableCount, "二次执行不得重复建表");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_setting", Integer.class), "已有设置数据不得丢失");
    }

    private static boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", tableName);
        return !rows.isEmpty();
    }
}
