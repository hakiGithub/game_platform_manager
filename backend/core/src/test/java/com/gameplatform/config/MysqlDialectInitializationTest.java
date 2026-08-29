package com.gameplatform.config;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mariadb.jdbc.Driver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.gameplatform.plugin.extension.ExtensionStoreInitializer;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 方言初始化集成测试（ADR-0015）。
 *
 * <p>使用 MariaDB4j 内嵌 MariaDB 实测「全新库启动 → 建表 + 种子数据 + 插件宽表」链路，
 * 覆盖 schema-mysql.sql / data-mysql.sql / DdlTemplate(MySQL) 的真实语法兼容性。</p>
 *
 * <p>运行策略：</p>
 * <ul>
 *   <li>优先使用环境变量 {@code MYSQL_UT_JDBC_URL} 指向的外部 MySQL/MariaDB
 *       （如 WSL 中的容器），凭证可选 {@code MYSQL_UT_USER} / {@code MYSQL_UT_PASSWORD}，
 *       缺省取 URL 中的 user/password 参数，再缺省 root/空密码</li>
 *   <li>未配置时启动内嵌 MariaDB4j；本机二进制不可用则<b>自动跳过</b>（不视为失败）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlDialectInitializationTest {

    private static final String[] ALL_TABLES = {
            "sys_user", "host_info", "game_metadata", "game_instance", "plugin_info",
            "backup_record", "task_record", "task_log",
            "scheduled_task", "scheduled_task_run", "scheduled_task_run_log"
    };

    private static DB embeddedDb;
    private static JdbcTemplate jdbcTemplate;
    private static DatabaseDialectResolver dialectResolver;
    private static DatabaseInitializer databaseInitializer;
    private static DatabaseScriptExecutor scriptExecutor;

    @BeforeAll
    static void startDatabase() {
        try {
            String externalUrl = System.getenv("MYSQL_UT_JDBC_URL");
            DataSource dataSource;
            String datasourceUrl;
            if (externalUrl != null && !externalUrl.isBlank()) {
                datasourceUrl = externalUrl;
                String user = firstNonBlank(System.getenv("MYSQL_UT_USER"), urlParam(externalUrl, "user"), "root");
                String password = firstNonBlank(System.getenv("MYSQL_UT_PASSWORD"), urlParam(externalUrl, "password"), "");
                dataSource = new SimpleDriverDataSource(new Driver(), datasourceUrl, user, password);
            } else {
                DBConfigurationBuilder config = DBConfigurationBuilder.newBuilder();
                config.setPort(0); // 0 = 随机空闲端口
                embeddedDb = DB.newEmbeddedDB(config.build());
                embeddedDb.start();
                embeddedDb.createDB("game_platform_ut");
                datasourceUrl = "jdbc:mariadb://127.0.0.1:" + config.getPort()
                        + "/game_platform_ut?user=root";
                dataSource = new SimpleDriverDataSource(new Driver(), datasourceUrl, "root", "");
            }
            jdbcTemplate = new JdbcTemplate(dataSource);
            scriptExecutor = new DatabaseScriptExecutor(jdbcTemplate);
            dialectResolver = new DatabaseDialectResolver(dataSource);
            databaseInitializer = new DatabaseInitializer(dialectResolver, scriptExecutor, jdbcTemplate);
            ReflectionTestUtils.setField(databaseInitializer, "datasourceUrl", datasourceUrl);
        } catch (Throwable t) {
            // MariaDB4j 无法在本机启动（缺二进制/端口占用等）：跳过而不是失败
            Assumptions.assumeTrue(false, "MariaDB 测试环境不可用，跳过 MySQL 方言 UT: " + t);
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (embeddedDb != null) {
            try {
                embeddedDb.stop();
            } catch (Throwable ignored) {
                // 进程随 JVM 退出的 DBShutdownHook 兜底清理
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("方言自动判定为 MYSQL（MariaDB 归入 MySQL 方言）")
    void dialectIsMysql() {
        assertEquals(DatabaseDialect.MYSQL, dialectResolver.resolve());
    }

    @Test
    @Order(2)
    @DisplayName("全新库启动：schema + data 一次建齐全部表")
    void initializesAllTablesOnEmptyDatabase() throws Exception {
        databaseInitializer.run();

        for (String table : ALL_TABLES) {
            assertTrue(scriptExecutor.tableExists(table), "表应已创建: " + table);
        }
    }

    @Test
    @Order(3)
    @DisplayName("种子数据写入且防重（重复执行不翻倍）")
    void seedsDataIdempotently() throws Exception {
        databaseInitializer.run(); // 第二次 run：表已存在，走非 SQLite 跳过分支，不得重复插入

        assertEquals(1, count("sys_user", "username = 'admin'"), "管理员账号应恰好一条");
        assertEquals(4, count("game_metadata", "1=1"), "游戏元数据应 4 条");
        assertEquals(3, count("plugin_info", "1=1"), "插件信息应 3 条");
    }

    @Test
    @Order(4)
    @DisplayName("迁移累积列已并入最新结构（game_instance.game_code）")
    void migratedColumnsPresent() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'game_instance' "
                        + "AND COLUMN_NAME = 'game_code'");
        assertEquals(1, columns.size(), "game_instance.game_code 应存在（V1.1 迁移累积列）");
    }

    @Test
    @Order(5)
    @DisplayName("非 SQLite 方言跳过 SQLite 专属迁移体系")
    void sqliteMigrationsSkipped() {
        // afterPropertiesSet 在非 SQLITE 方言下应直接返回，不触碰 PRAGMA/sqlite_master
        SchemaMigrationRunner runner = new SchemaMigrationRunner(dialectResolver, jdbcTemplate);
        runner.afterPropertiesSet();
    }

    @Test
    @Order(6)
    @DisplayName("插件扩展存储宽表按 MySQL 方言创建（DdlTemplate）")
    void extensionWideTableCreated() {
        ExtensionStoreInitializer initializer =
                new ExtensionStoreInitializer(jdbcTemplate, dialectResolver);
        initializer.init();
        assertTrue(scriptExecutor.tableExists("extensions"), "插件宽表 extensions 应已创建");
    }

    private int count(String table, String where) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class);
        return count == null ? 0 : count.intValue();
    }

    private static String urlParam(String url, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[?&]" + name + "=([^&]*)").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
