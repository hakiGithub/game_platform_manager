package com.gameplatform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库Schema迁移执行器
 * 启动时自动执行幂等的ALTER TABLE语句，确保新增列存在
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureColumnExists("game_instance", "runtime_metadata", "TEXT");
        // V1.5: 任务中心模块建表（task_record + task_log）
        ensureSqlFileExecuted("task_record", "db/migration/V1.5__task_center.sql");
    }

    /**
     * 确保指定 SQL 文件已执行（通过检查标志性表是否存在判断）
     *
     * <p>用于执行 migration 目录下的建表 SQL。SQL 文件应使用 CREATE TABLE IF NOT EXISTS
     * 确保幂等。SQLite 不支持一次执行多条语句，需逐条执行。
     *
     * @param markerTable 标志性表名（用于判断是否已执行）
     * @param sqlFile     classpath 下的 SQL 文件路径
     */
    private void ensureSqlFileExecuted(String markerTable, String sqlFile) {
        try {
            if (isTableExists(markerTable)) {
                log.debug("Schema迁移: 表 {} 已存在，跳过 {}", markerTable, sqlFile);
                return;
            }
            log.info("Schema迁移: 表 {} 不存在，执行 SQL 文件 {}", markerTable, sqlFile);
            ClassPathResource resource = new ClassPathResource(sqlFile);
            String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            // SQLite 不支持一次执行多条语句，按分号拆分逐条执行
            String[] statements = sql.split(";");
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    jdbcTemplate.execute(trimmed);
                }
            }
            log.info("Schema迁移: SQL 文件 {} 执行完成", sqlFile);
        } catch (Exception e) {
            log.error("Schema迁移: 执行 SQL 文件 {} 失败: {}", sqlFile, e.getMessage());
        }
    }

    /**
     * 检查表是否存在
     */
    private boolean isTableExists(String tableName) {
        try {
            String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?";
            String result = jdbcTemplate.queryForObject(sql, String.class, tableName);
            return result != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保指定表的列存在，不存在则添加
     *
     * @param tableName  表名
     * @param columnName 列名
     * @param columnType 列类型（如 TEXT）
     */
    private void ensureColumnExists(String tableName, String columnName, String columnType) {
        try {
            List<String> columns = new ArrayList<>();
            jdbcTemplate.query("PRAGMA table_info(" + tableName + ")", (ResultSet rs) -> {
                columns.add(rs.getString("name"));
            });

            if (!columns.contains(columnName)) {
                String sql = String.format("ALTER TABLE %s ADD COLUMN %s %s",
                        tableName, columnName, columnType);
                jdbcTemplate.execute(sql);
                log.info("Schema迁移: 已添加列 {}.{} {}", tableName, columnName, columnType);
            } else {
                log.debug("Schema迁移: 列 {}.{} 已存在，跳过", tableName, columnName);
            }
        } catch (Exception e) {
            log.error("Schema迁移失败: {}.{} - {}", tableName, columnName, e.getMessage());
        }
    }
}
