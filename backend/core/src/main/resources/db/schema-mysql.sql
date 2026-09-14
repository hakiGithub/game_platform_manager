-- =====================================================
-- Game Platform Manager 数据库表结构
-- 数据库: MySQL 8+（兼容 MariaDB 10.5+）
-- 版本: 1.1.0（ADR-0015 多方言拆分）
--
-- 约定:
-- 1. 本文件内容 = 最新完整结构（核心 6 表 + 任务中心/定时计划表），
--    全新库一步到位建表，不重放 db/migration/ 的 SQLite 增量脚本。
-- 2. 索引一律内联在 CREATE TABLE 的 KEY 子句中——MySQL 不支持
--    CREATE INDEX IF NOT EXISTS，内联后随表的 IF NOT EXISTS 天然幂等。
-- 3. 种子数据见 data-mysql.sql（INSERT IGNORE 防重）。
-- =====================================================

-- =====================================================
-- 1. 用户表 (sys_user)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(50) NOT NULL,
    password_hash   VARCHAR(128) NOT NULL,
    jwt_secret      VARCHAR(256),
    last_login_time DATETIME,
    last_login_ip   VARCHAR(50),
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted      TINYINT DEFAULT 0,
    remark          TEXT,
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_is_deleted (is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 2. 主机信息表 (host_info)
-- =====================================================
CREATE TABLE IF NOT EXISTS host_info (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    host_name        VARCHAR(100) NOT NULL,
    ip_address       VARCHAR(50) NOT NULL,
    ssh_port         INT DEFAULT 22,
    ssh_user         VARCHAR(50) NOT NULL,
    ssh_password     TEXT,                        -- SSH密码(加密存储)
    ssh_private_key  TEXT,
    tags             TEXT,                        -- 标签(JSON数组格式)
    remark           TEXT,
    online_status    INT DEFAULT 0,
    os_type          VARCHAR(50),                 -- 操作系统类型
    os_version       VARCHAR(100),                -- 操作系统版本
    cpu_cores        INT,                         -- CPU核心数
    memory_mb        BIGINT,                      -- 内存大小(MB)
    disk_gb          BIGINT,                      -- 磁盘大小(GB)
    cpu_usage        DECIMAL(5,2),
    memory_usage     DECIMAL(5,2),
    disk_usage       DECIMAL(5,2),
    last_check_time  DATETIME,
    is_lan_host      TINYINT DEFAULT 0,           -- 是否局域网主机 0-否 1-是（详见 ADR-0004）
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT DEFAULT 0,
    UNIQUE KEY uk_host_info_ip_address (ip_address),
    KEY idx_host_info_online_status (online_status),
    KEY idx_host_info_is_deleted (is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 3. 游戏元数据表 (game_metadata)
-- =====================================================
CREATE TABLE IF NOT EXISTS game_metadata (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_name              VARCHAR(100) NOT NULL,
    game_code              VARCHAR(50) NOT NULL,
    description            TEXT,
    supported_deploy_types TEXT,                 -- JSON数组: ["docker", "native"]
    default_port           INT,
    environment_deps       TEXT,                 -- JSON对象
    deploy_config          TEXT,                 -- JSON对象
    custom_operations      TEXT,                 -- JSON对象
    icon_url               VARCHAR(500),
    create_time            DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted             TINYINT DEFAULT 0,
    remark                 TEXT,
    UNIQUE KEY uk_game_metadata_game_code (game_code),
    KEY idx_game_metadata_is_deleted (is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 4. 游戏实例表 (game_instance)
-- =====================================================
CREATE TABLE IF NOT EXISTS game_instance (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_name    VARCHAR(100) NOT NULL,
    host_id          BIGINT NOT NULL,
    game_id          BIGINT NOT NULL,
    game_code        VARCHAR(64),
    deploy_type      VARCHAR(20) NOT NULL,        -- docker/native
    port_config      TEXT,                        -- JSON对象
    run_status       INT DEFAULT 0,               -- 0-已停止 1-运行中 2-异常
    online_players   INT DEFAULT 0,
    config_info      TEXT,                        -- JSON对象
    install_path     VARCHAR(500),
    start_command    TEXT,
    stop_command     TEXT,
    database_config  TEXT,                        -- JSON对象
    save_path        VARCHAR(500),
    config_path      VARCHAR(500),
    last_backup_time DATETIME,
    runtime_metadata TEXT,                        -- JSON对象，存储运行时元数据
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT DEFAULT 0,
    remark           TEXT,
    UNIQUE KEY uk_game_instance_host_instance (host_id, instance_name),
    KEY idx_game_instance_game_id (game_id),
    KEY idx_game_instance_run_status (run_status),
    KEY idx_game_instance_is_deleted (is_deleted),
    CONSTRAINT fk_game_instance_host FOREIGN KEY (host_id) REFERENCES host_info (id),
    CONSTRAINT fk_game_instance_game FOREIGN KEY (game_id) REFERENCES game_metadata (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 5. 插件信息表 (plugin_info)
-- =====================================================
CREATE TABLE IF NOT EXISTS plugin_info (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    plugin_id        VARCHAR(100) NOT NULL,
    plugin_name      VARCHAR(100) NOT NULL,
    version          VARCHAR(20) NOT NULL,
    status           INT DEFAULT 0,               -- 0-禁用 1-启用
    description      TEXT,
    extension_points TEXT,                        -- JSON对象
    config_schema    TEXT,                        -- JSON对象
    plugin_type      VARCHAR(50),                 -- 插件类型
    game_code        VARCHAR(50),                 -- 关联游戏编码
    file_path        VARCHAR(500),                -- 插件 JAR 路径
    runtime_state    VARCHAR(20),                 -- 运行时状态
    load_time        DATETIME,                    -- 加载时间
    start_time       DATETIME,                    -- 启动时间
    author           VARCHAR(100),
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT DEFAULT 0,
    remark           TEXT,
    UNIQUE KEY uk_plugin_info_plugin_id (plugin_id),
    KEY idx_plugin_info_status (status),
    KEY idx_plugin_info_is_deleted (is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 6. 备份记录表 (backup_record)
-- =====================================================
CREATE TABLE IF NOT EXISTS backup_record (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id    BIGINT NOT NULL,
    backup_name    VARCHAR(200) NOT NULL,
    backup_type    VARCHAR(20) NOT NULL,          -- FULL-全量, INCREMENTAL-增量
    target_type    VARCHAR(20) NOT NULL,          -- DATABASE-数据库, FILES-文件
    database_type  VARCHAR(20),                   -- MYSQL, POSTGRESQL, SQLITE
    file_size      BIGINT,
    file_path      VARCHAR(500),
    file_md5       VARCHAR(32),
    description    TEXT,
    status         INT DEFAULT 0,                 -- 0-备份中, 1-成功, 2-失败
    error_message  TEXT,
    backup_time    DATETIME,
    complete_time  DATETIME,
    progress       INT DEFAULT 0,                 -- 0-100
    source_path    VARCHAR(500),
    retry_count    INT DEFAULT 0,
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted     TINYINT DEFAULT 0,
    remark         TEXT,
    KEY idx_backup_record_instance_id (instance_id),
    KEY idx_backup_record_target_type (target_type),
    KEY idx_backup_record_status (status),
    KEY idx_backup_record_backup_time (backup_time),
    KEY idx_backup_record_is_deleted (is_deleted),
    CONSTRAINT fk_backup_record_instance FOREIGN KEY (instance_id) REFERENCES game_instance (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 7. 任务记录表 (task_record，原 V1.5 迁移建表，现并入建表脚本)
-- =====================================================
CREATE TABLE IF NOT EXISTS task_record (
    id               VARCHAR(64) PRIMARY KEY,     -- 雪花ID（应用层生成）
    task_type        VARCHAR(100) NOT NULL,       -- 任务类型: crawl/deploy/backup/restart/export
    source           VARCHAR(50) NOT NULL,        -- 任务来源(大写): MAIN / L4D2 / {gameCode}
    status           VARCHAR(20) NOT NULL,        -- 状态: PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
    submitter        VARCHAR(50),                 -- 提交者用户名或 SYSTEM
    scope_type       VARCHAR(50) DEFAULT 'GLOBAL', -- 作用域: INSTANCE/HOST/GLOBAL
    scope_key        VARCHAR(100),                -- 作用域键，如 instanceId=55
    scope_name       VARCHAR(200),                -- 作用域名称，如实例名
    payload          TEXT,                        -- 输入参数 JSON
    result           TEXT,                        -- 输出结果 JSON
    result_summary   VARCHAR(500),                -- Handler 生成的结果摘要
    progress         INT DEFAULT 0,               -- 进度百分比 0-100
    progress_message VARCHAR(500),                -- 进度描述
    error_message    TEXT,
    stack_trace      TEXT,
    retry_count      INT DEFAULT 0,
    parent_task_id   VARCHAR(64),
    started_at       TIMESTAMP NULL,
    completed_at     TIMESTAMP NULL,
    duration_ms      BIGINT,
    create_time      TIMESTAMP NULL,
    update_time      TIMESTAMP NULL,
    is_deleted       TINYINT DEFAULT 0,
    remark           TEXT,
    KEY idx_task_record_status (status),
    KEY idx_task_record_source (source),
    KEY idx_task_record_task_type (task_type),
    KEY idx_task_record_scope_key (scope_key),
    KEY idx_task_record_create_time (create_time),
    KEY idx_task_record_parent_task_id (parent_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 8. 任务日志表 (task_log)
-- =====================================================
CREATE TABLE IF NOT EXISTS task_log (
    id          VARCHAR(64) PRIMARY KEY,          -- 雪花ID（应用层生成）
    task_id     VARCHAR(64) NOT NULL,             -- 关联 task_record.id
    level       VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message     TEXT NOT NULL,
    create_time TIMESTAMP NULL,
    KEY idx_task_log_task_id (task_id),
    KEY idx_task_log_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 9. 定时计划表 (scheduled_task，原 V1.7 迁移建表，现并入建表脚本)
-- =====================================================
CREATE TABLE IF NOT EXISTS scheduled_task (
    id              VARCHAR(64) PRIMARY KEY,      -- 雪花ID（应用层生成）
    name            VARCHAR(200) NOT NULL,        -- 计划名称
    handler_key     VARCHAR(100) NOT NULL,        -- ScheduledTaskHandler 的 key
    cron            VARCHAR(100) NOT NULL,        -- cron 表达式（6 位，Spring 语法，服务器时区）
    payload         TEXT,                         -- payload 模板 JSON
    enabled         TINYINT NOT NULL DEFAULT 1,   -- 用户启用意图: 1-启用 0-禁用
    paused          TINYINT NOT NULL DEFAULT 0,   -- 系统暂停（如插件停用）: 1-暂停
    pause_reason    VARCHAR(500),
    source          VARCHAR(50) NOT NULL,         -- 来源(大写): MAIN / {gameCode}
    plugin_id       VARCHAR(100),                 -- 插件ID（MAIN 来源为 NULL）
    declaration_key VARCHAR(200),                 -- 声明稳定键（pluginId:key）
    user_modified   TINYINT NOT NULL DEFAULT 0,   -- 用户是否改过（声明 upsert 跳过）: 1-是
    create_by       VARCHAR(64),
    update_by       VARCHAR(64),
    create_time     TIMESTAMP NULL,
    update_time     TIMESTAMP NULL,
    is_deleted      TINYINT DEFAULT 0,            -- 逻辑删除 = 声明复活墓碑
    remark          TEXT,
    KEY idx_scheduled_task_source (source),
    KEY idx_scheduled_task_handler_key (handler_key),
    KEY idx_scheduled_task_plugin_id (plugin_id),
    KEY idx_scheduled_task_declaration_key (declaration_key),
    KEY idx_scheduled_task_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 10. 定时计划执行记录表 (scheduled_task_run)
-- =====================================================
CREATE TABLE IF NOT EXISTS scheduled_task_run (
    id               VARCHAR(64) PRIMARY KEY,     -- 雪花ID（应用层生成，即 runId）
    schedule_id      VARCHAR(64) NOT NULL,        -- 关联 scheduled_task.id
    schedule_name    VARCHAR(200),                -- 计划名称快照
    trigger_type     VARCHAR(20) NOT NULL,        -- 触发方式: CRON / MANUAL
    status           VARCHAR(20) NOT NULL,        -- RUNNING / SUCCEEDED / FAILED / CANCELLED / SKIPPED
    payload          TEXT,                        -- 本次执行的 payload 快照
    result           TEXT,                        -- 执行结果 JSON
    error_message    TEXT,                        -- SKIPPED 原因 / 失败错误信息
    progress         INT DEFAULT 0,
    progress_message VARCHAR(500),
    started_at       TIMESTAMP NULL,
    completed_at     TIMESTAMP NULL,
    duration_ms      BIGINT,
    create_time      TIMESTAMP NULL,
    update_time      TIMESTAMP NULL,
    KEY idx_scheduled_task_run_schedule_id (schedule_id),
    KEY idx_scheduled_task_run_status (status),
    KEY idx_scheduled_task_run_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =====================================================
-- 11. 定时计划执行日志表 (scheduled_task_run_log)
-- =====================================================
CREATE TABLE IF NOT EXISTS scheduled_task_run_log (
    id          VARCHAR(64) PRIMARY KEY,          -- 雪花ID（应用层生成）
    run_id      VARCHAR(64) NOT NULL,             -- 关联 scheduled_task_run.id
    level       VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message     TEXT NOT NULL,
    create_time TIMESTAMP NULL,
    KEY idx_scheduled_task_run_log_run_id (run_id),
    KEY idx_scheduled_task_run_log_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 系统设置表（SystemController 设置持久化，按 platform/ssh/docker 分组存储 JSON）
CREATE TABLE IF NOT EXISTS sys_setting (
  setting_group VARCHAR(50) PRIMARY KEY,
  setting_value TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
