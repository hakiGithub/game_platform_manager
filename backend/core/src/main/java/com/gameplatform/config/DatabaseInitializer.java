package com.gameplatform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据库初始化器
 * 应用启动时自动初始化数据库
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Override
    public void run(String... args) throws Exception {
        log.info("开始初始化数据库...");
        
        try {
            // 确保数据库目录存在
            ensureDatabaseDirectory();
            
            // 检查表是否存在
            if (!isTableExists("sys_user")) {
                log.info("数据库表不存在，开始创建表结构...");
                executeSchemaSql();
                executeDataSql();
                log.info("数据库初始化完成");
            } else {
                log.info("数据库表已存在，检查并执行数据库迁移...");
                runMigrations();
                log.info("数据库迁移检查完成");
            }
        } catch (Exception e) {
            log.error("数据库初始化失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 确保数据库目录存在
     */
    private void ensureDatabaseDirectory() throws Exception {
        // 从JDBC URL中提取数据库文件路径
        String dbPath = datasourceUrl.replace("jdbc:sqlite:", "");
        Path path = Paths.get(dbPath).getParent();
        if (path != null && !Files.exists(path)) {
            Files.createDirectories(path);
            log.info("创建数据库目录: {}", path);
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
     * 执行建表SQL
     */
    private void executeSchemaSql() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/schema.sql");
        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        
        // SQLite不支持一次执行多条语句，需要逐条执行
        String[] statements = sql.split(";");
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
            }
        }
        log.info("表结构创建完成");
    }

    /**
     * 执行初始化数据SQL
     */
    private void executeDataSql() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/data.sql");
        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        
        // SQLite不支持一次执行多条语句，需要逐条执行
        String[] statements = sql.split(";");
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                jdbcTemplate.execute(trimmed);
            }
        }
        log.info("初始数据导入完成");
    }

    /**
     * 执行数据库迁移
     */
    private void runMigrations() {
        // V1.1: 添加缺失的列
        addColumnIfNotExists("operation_log", "remark", "TEXT");
        addColumnIfNotExists("game_instance", "database_config", "TEXT");
        addColumnIfNotExists("game_instance", "save_path", "VARCHAR(500)");
        addColumnIfNotExists("game_instance", "config_path", "VARCHAR(500)");
        addColumnIfNotExists("game_instance", "last_backup_time", "DATETIME");
        addColumnIfNotExists("game_instance", "game_code", "VARCHAR(64)");
        addColumnIfNotExists("plugin_info", "plugin_type", "VARCHAR(50)");
        addColumnIfNotExists("plugin_info", "game_code", "VARCHAR(50)");
        addColumnIfNotExists("plugin_info", "file_path", "VARCHAR(500)");
        addColumnIfNotExists("plugin_info", "runtime_state", "VARCHAR(20)");
        addColumnIfNotExists("plugin_info", "load_time", "DATETIME");
        addColumnIfNotExists("plugin_info", "start_time", "DATETIME");

        // V1.5: game_instance 表将 instance_name 单列 UNIQUE 改为 (host_id, instance_name) 联合唯一
        migrateInstanceNameUniqueToComposite();
    }

    /**
     * 将 game_instance 表的 instance_name 单列唯一约束迁移为 (host_id, instance_name) 联合唯一约束。
     * SQLite 不支持 ALTER TABLE DROP CONSTRAINT，需通过重建表实现。
     * 幂等：已迁移过的表（无 instance_name UNIQUE 列约束）不会重复执行。
     */
    private void migrateInstanceNameUniqueToComposite() {
        try {
            // 查询当前建表 SQL，判断是否为旧版本（含 instance_name NOT NULL UNIQUE 列约束）
            String tableSql = jdbcTemplate.queryForObject(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='game_instance'",
                    String.class);
            if (tableSql == null) {
                return;
            }
            // 旧版本特征：instance_name 列带 UNIQUE 约束
            if (!tableSql.contains("instance_name") || !tableSql.toUpperCase().contains("UNIQUE")) {
                log.debug("game_instance 表无需迁移联合唯一索引");
                return;
            }
            // 进一步判断：只有当 UNIQUE 紧跟 instance_name 列定义时才需迁移
            // 新版本使用 UNIQUE(host_id, instance_name) 表级约束，不含 "instance_name VARCHAR(100) NOT NULL UNIQUE"
            if (!tableSql.toUpperCase().matches("(?s).*INSTANCE_NAME\\s+VARCHAR\\(100\\)\\s+NOT\\s+NULL\\s+UNIQUE.*")) {
                log.debug("game_instance 表已使用联合唯一约束，无需迁移");
                return;
            }

            log.info("开始迁移 game_instance 表：instance_name UNIQUE → (host_id, instance_name) 联合唯一");

            // 1. 创建新表（无 instance_name 单列 UNIQUE，使用表级联合唯一约束）
            jdbcTemplate.execute(
                    "CREATE TABLE game_instance_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "instance_name VARCHAR(100) NOT NULL, " +
                    "host_id INTEGER NOT NULL, " +
                    "game_id INTEGER NOT NULL, " +
                    "game_code VARCHAR(64), " +
                    "deploy_type VARCHAR(20) NOT NULL, " +
                    "port_config TEXT, " +
                    "run_status INTEGER DEFAULT 0, " +
                    "online_players INTEGER DEFAULT 0, " +
                    "config_info TEXT, " +
                    "install_path VARCHAR(500), " +
                    "start_command TEXT, " +
                    "stop_command TEXT, " +
                    "database_config TEXT, " +
                    "save_path VARCHAR(500), " +
                    "config_path VARCHAR(500), " +
                    "last_backup_time DATETIME, " +
                    "runtime_metadata TEXT, " +
                    "create_time DATETIME DEFAULT (datetime('now', 'localtime')), " +
                    "update_time DATETIME DEFAULT (datetime('now', 'localtime')), " +
                    "is_deleted INTEGER DEFAULT 0, " +
                    "remark TEXT, " +
                    "FOREIGN KEY (host_id) REFERENCES host_info(id), " +
                    "FOREIGN KEY (game_id) REFERENCES game_metadata(id), " +
                    "UNIQUE(host_id, instance_name)" +
                    ")");

            // 2. 复制数据（按列显式列出，避免列顺序不一致）
            jdbcTemplate.execute(
                    "INSERT INTO game_instance_new (" +
                    "id, instance_name, host_id, game_id, game_code, deploy_type, port_config, " +
                    "run_status, online_players, config_info, install_path, start_command, stop_command, " +
                    "database_config, save_path, config_path, last_backup_time, runtime_metadata, " +
                    "create_time, update_time, is_deleted, remark" +
                    ") SELECT " +
                    "id, instance_name, host_id, game_id, game_code, deploy_type, port_config, " +
                    "run_status, online_players, config_info, install_path, start_command, stop_command, " +
                    "database_config, save_path, config_path, last_backup_time, runtime_metadata, " +
                    "create_time, update_time, is_deleted, remark FROM game_instance");

            // 3. 删除旧表并重命名新表
            jdbcTemplate.execute("DROP TABLE game_instance");
            jdbcTemplate.execute("ALTER TABLE game_instance_new RENAME TO game_instance");

            // 4. 重建索引
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_game_instance_host_id ON game_instance(host_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_game_instance_game_id ON game_instance(game_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_game_instance_run_status ON game_instance(run_status)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_game_instance_is_deleted ON game_instance(is_deleted)");

            log.info("game_instance 表联合唯一索引迁移完成");
        } catch (Exception e) {
            log.error("迁移 game_instance 联合唯一索引失败", e);
        }
    }

    /**
     * 如果列不存在则添加
     */
    private void addColumnIfNotExists(String tableName, String columnName, String columnType) {
        try {
            if (!isColumnExists(tableName, columnName)) {
                String sql = String.format("ALTER TABLE %s ADD COLUMN %s %s", tableName, columnName, columnType);
                jdbcTemplate.execute(sql);
                log.info("添加列 {}.{}", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("添加列 {}.{} 失败: {}", tableName, columnName, e.getMessage());
        }
    }

    /**
     * 检查列是否存在
     */
    private boolean isColumnExists(String tableName, String columnName) {
        try {
            String sql = String.format("PRAGMA table_info(%s)", tableName);
            return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("name"))
                    .stream()
                    .anyMatch(name -> name.equalsIgnoreCase(columnName));
        } catch (Exception e) {
            log.warn("检查列 {}.{} 是否存在失败: {}", tableName, columnName, e.getMessage());
            return false;
        }
    }

}
