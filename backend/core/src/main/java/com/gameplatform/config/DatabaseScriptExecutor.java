package com.gameplatform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;

/**
 * 数据库脚本执行与表存在性检查（ADR-0015）。
 *
 * <p>方言无关的公共能力：
 * <ul>
 *   <li>表存在性检查：走 JDBC {@link java.sql.DatabaseMetaData}，替代原来的
 *       sqlite_master 查询（MySQL/PostgreSQL 无此系统表）</li>
 *   <li>classpath SQL 脚本执行：剔除整行注释后按分号拆分逐条执行
 *       （SQLite 限制单语句；MySQL/PostgreSQL 逐条执行同样安全）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseScriptExecutor {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 检查表是否存在（通过当前连接的数据库元数据）。
     *
     * @param tableName 表名（平台所有表名均为小写）
     */
    public boolean tableExists(String tableName) {
        try {
            Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) con -> {
                ResultSet rs = con.getMetaData().getTables(
                        con.getCatalog(), con.getSchema(), tableName, new String[]{"TABLE"});
                try (rs) {
                    return rs.next();
                }
            });
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("检查表 {} 是否存在失败: {}", tableName, e.getMessage());
            return false;
        }
    }

    /**
     * 执行 classpath 下的 SQL 脚本（建表 / 种子数据）。
     *
     * <p>先剔除整行注释再按分号拆分逐条执行；调用方需保证脚本幂等
     * （CREATE TABLE IF NOT EXISTS / INSERT IGNORE / ON CONFLICT DO NOTHING）。
     *
     * @param location classpath 资源路径，如 db/schema-mysql.sql
     */
    public void executeScript(String location) throws Exception {
        ClassPathResource resource = new ClassPathResource(location);
        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        String stripped = sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        String[] statements = stripped.split(";");
        int executed = 0;
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
                executed++;
            }
        }
        log.info("SQL 脚本 {} 执行完成（{} 条语句）", location, executed);
    }
}
