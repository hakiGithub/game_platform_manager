package com.gameplatform.config;

import com.gameplatform.plugin.extension.ExtensionStoreInitializer;
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
 * SQLite 方言全新库初始化测试（ADR-0015）。
 *
 * <p>用临时文件库模拟真实启动顺序（SchemaMigrationRunner 先于 DatabaseInitializer），
 * 验证：方言 schema 建表、种子数据、迁移累积列并入、SQLite 迁移体系幂等重跑。</p>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
class SqliteDialectInitializationTest {

    private static final String[] ALL_TABLES = {
            "sys_user", "host_info", "game_metadata", "game_instance", "plugin_info",
            "backup_record", "task_record", "task_log",
            "scheduled_task", "scheduled_task_run", "scheduled_task_run_log"
    };

    @TempDir
    static Path tempDir;

    private static SingleConnectionDataSource dataSource;

    @AfterAll
    static void releaseConnection() {
        // Windows 下必须先释放连接，否则 SQLite 文件被锁，JUnit 删除 @TempDir 失败
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("SQLite 全新库：模拟启动链路建齐全部表并导入种子")
    void freshInitialization() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("ut.sqlite");
        dataSource = new SingleConnectionDataSource(url, true);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseScriptExecutor scriptExecutor = new DatabaseScriptExecutor(jdbcTemplate);

        DatabaseDialectResolver dialectResolver = new DatabaseDialectResolver(dataSource);
        assertEquals(DatabaseDialect.SQLITE, dialectResolver.resolve(), "应判定为 SQLite 方言");

        // 1. 模拟启动顺序：迁移 Runner（InitializingBean）先跑，V1.5/V1.7 建任务/计划表
        SchemaMigrationRunner migrationRunner = new SchemaMigrationRunner(dialectResolver, jdbcTemplate);
        migrationRunner.afterPropertiesSet();
        assertTrue(scriptExecutor.tableExists("task_record"), "V1.5 任务中心表应由迁移 Runner 创建");

        // 2. DatabaseInitializer 建核心表 + 种子数据
        DatabaseInitializer initializer = new DatabaseInitializer(
                dialectResolver, scriptExecutor, jdbcTemplate);
        org.springframework.test.util.ReflectionTestUtils.setField(initializer, "datasourceUrl", url);
        initializer.run();

        for (String table : ALL_TABLES) {
            assertTrue(scriptExecutor.tableExists(table), "表应已创建: " + table);
        }

        // 3. 迁移累积列并入最新结构（不再依赖迁移补齐）
        List<Map<String, Object>> gameCode = jdbcTemplate.queryForList("PRAGMA table_info(game_instance)")
                .stream().filter(col -> "game_code".equals(col.get("name"))).toList();
        assertEquals(1, gameCode.size(), "game_instance.game_code 应直接存在于建表脚本");
        List<Map<String, Object>> pluginType = jdbcTemplate.queryForList("PRAGMA table_info(plugin_info)")
                .stream().filter(col -> "plugin_type".equals(col.get("name"))).toList();
        assertEquals(1, pluginType.size(), "plugin_info.plugin_type 应直接存在于建表脚本");

        // 4. 种子数据 + 插件宽表
        assertEquals(1, count(jdbcTemplate, "sys_user", "username = 'admin'"), "管理员账号应恰好一条");
        assertEquals(4, count(jdbcTemplate, "game_metadata", "1=1"), "游戏元数据应 4 条");
        new ExtensionStoreInitializer(jdbcTemplate, dialectResolver).init();
        assertTrue(scriptExecutor.tableExists("extensions"), "插件宽表 extensions 应已创建");

        // 5. 幂等重跑：SQLite 迁移分支 + 初始化器均不得报错或重复插入
        migrationRunner.afterPropertiesSet();
        initializer.run();
        assertEquals(4, count(jdbcTemplate, "game_metadata", "1=1"), "重跑后种子数据不得翻倍");
        assertEquals(1, count(jdbcTemplate, "sys_user", "1=1"), "重跑后用户不得翻倍");
    }

    private static int count(JdbcTemplate jdbcTemplate, String table, String where) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class);
        return count == null ? 0 : count.intValue();
    }
}
