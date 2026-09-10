-- =====================================================
-- Game Platform Manager 数据库表结构
-- 数据库: PostgreSQL 12+
-- 版本: 1.1.0（ADR-0015 多方言拆分）
--
-- 约定:
-- 1. 本文件内容 = 最新完整结构（核心 6 表 + 任务中心/定时计划表），
--    全新库一步到位建表，不重放 db/migration/ 的 SQLite 增量脚本。
-- 2. 自增主键用 BIGSERIAL（对应 SQLite INTEGER PRIMARY KEY AUTOINCREMENT / MySQL BIGINT AUTO_INCREMENT）。
-- 3. PG 支持 CREATE INDEX IF NOT EXISTS，索引独立语句维护。
-- 4. 种子数据见 data-postgresql.sql（ON CONFLICT DO NOTHING 防重）。
-- =====================================================

-- =====================================================
-- 1. 用户表 (sys_user)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50) NOT NULL UNIQUE,
    password_hash   VARCHAR(128) NOT NULL,
    jwt_secret      VARCHAR(256),
    last_login_time TIMESTAMP,
    last_login_ip   VARCHAR(50),
    create_time     TIMESTAMP DEFAULT LOCALTIMESTAMP,
    update_time     TIMESTAMP DEFAULT LOCALTIMESTAMP,
    is_deleted      INTEGER DEFAULT 0,
    remark          TEXT
);

CREATE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username);
CREATE INDEX IF NOT EXISTS idx_sys_user_is_deleted ON sys_user(is_deleted);

-- =====================================================
-- 2. 主机信息表 (host_info)
-- =====================================================
CREATE TABLE IF NOT EXISTS host_info (
    id               BIGSERIAL PRIMARY KEY,
    host_name        VARCHAR(100) NOT NULL,
    ip_address       VARCHAR(50) NOT NULL UNIQUE,
    ssh_port         INTEGER DEFAULT 22,
    ssh_user         VARCHAR(50) NOT NULL,
    ssh_password     TEXT,                        -- SSH密码(加密存储)
    ssh_private_key  TEXT,
    tags             TEXT,                        -- 标签(JSON数组格式)
    remark           TEXT,
    online_status    INTEGER DEFAULT 0,
    os_type          VARCHAR(50),                 -- 操作系统类型
    os_version       VARCHAR(100),                -- 操作系统版本
    cpu_cores        INTEGER,                     -- CPU核心数
    memory_mb        BIGINT,                      -- 内存大小(MB)
    disk_gb          BIGINT,                      -- 磁盘大小(GB)
    cpu_usage        DECIMAL(5,2),
    memory_usage     DECIMAL(5,2),
    disk_usage       DECIMAL(5,2),
    last_check_time  TIMESTAMP,
    is_lan_host      BOOLEAN DEFAULT FALSE,       -- 是否局域网主机（详见 ADR-0004）
    create_time      TIMESTAMP DEFAULT LOCALTIMESTAMP,
    update_time      TIMESTAMP DEFAULT LOCALTIMESTAMP,
    is_deleted       INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_host_info_ip_address ON host_info(ip_address);
CREATE INDEX IF NOT EXISTS idx_host_info_online_status ON host_info(online_status);
CREATE INDEX IF NOT EXISTS idx_host_info_is_deleted ON host_info(is_deleted);

-- =====================================================
-- 3. 游戏元数据表 (game_metadata)
-- =====================================================
CREATE TABLE IF NOT EXISTS game_metadata (
    id                     BIGSERIAL PRIMARY KEY,
    game_name              VARCHAR(100) NOT NULL,
    game_code              VARCHAR(50) NOT NULL UNIQUE,
    description            TEXT,
    supported_deploy_types TEXT,                  -- JSON数组: ["docker", "native"]
    default_port           INTEGER,
    environment_deps       TEXT,                  -- JSON对象
    deploy_config          TEXT,                  -- JSON对象
    custom_operations      TEXT,                  -- JSON对象
    icon_url               VARCHAR(500),
    create_time            TIMESTAMP DEFAULT LOCALTIMESTAMP,
    update_time            TIMESTAMP DEFAULT LOCALTIMESTAMP,
    is_deleted             INTEGER DEFAULT 0,
    remark                 TEXT
);

CREATE INDEX IF NOT EXISTS idx_game_metadata_game_code ON game_metadata(game_code);
CREATE INDEX IF NOT EXISTS idx_game_metadata_is_deleted ON game_metadata(is_deleted);

-- =====================================================
-- 4. 游戏实例表 (game_instance)
-- =====================================================
CREATE TABLE IF NOT EXISTS game_instance (
    id               BIGSERIAL PRIMARY KEY,
    instance_name    VARCHAR(100) NOT NULL,
    host_id          BIGINT NOT NULL,
    game_id          BIGINT NOT NULL,
    game_code        VARCHAR(64),
    deploy_type      VARCHAR(20) NOT NULL,        -- docker/native
    port_config      TEXT,                        -- JSON对象
    run_status       INTEGER DEFAULT 0,           -- 0-已停止 1-运行中 2-异常
    online_players   INTEGER DEFAULT 0,
    config_info      TEXT,                        -- JSON对象
    install_path     VARCHAR(500),
    start_command    TEXT,
    stop_command     TEXT,
    database_config  TEXT,                        -- JSON对象
    save_path        VARCHAR(500),
    config_path      VARCHAR(500),
    last_backup_time TIMESTAMP,
    runtime_metadata TEXT,                        -- JSON对象，存储运行时元数据
    create_time      TIMESTAMP DEFAULT LOCALTIMESTAMP,
    update_time      TIMESTAMP DEFAULT LOCALTIMESTAMP,
    is_deleted       INTEGER DEFAULT 0,
    remark           TEXT,
    FOREIGN KEY (host_id) REFERENCES host_info(id),
    FOREIGN KEY (game_id) REFERENCES game_metadata(id),
    UNIQUE(host_id, instance_name)
);

CREATE INDEX IF NOT EXISTS idx_game_instance_host_id ON game_instance(host_id);
CREATE INDEX IF NOT EXISTS idx_game_instance_game_id ON game_instance(game_id);
CREATE INDEX IF NOT EXISTS idx_game_instance_run_status ON game_instance(run_status);
CREATE INDEX IF NOT EXISTS idx_game_instance_is_deleted ON game_instance(is_deleted);

-- =====================================================
-- 5. 插件信息表 (plugin_info)
-- =====================================================
CREATE TABLE IF NOT EXISTS plugin_info (
    id               BIGSERIAL PRIMARY KEY,
    plugin_id        VARCHAR(100) NOT NULL UNIQUE,
    plugin_name      VARCHAR(100) NOT NULL,
    version          VARCHAR(20) NOT NULL,
    status           INTEGER DEFAULT 0,           -- 0-禁用 1-启用
    description      TEXT,
    extension_points TEXT,                        -- JSON对象
    config_schema    TEXT,                        -- JSON对象
    plugin_type      VARCHAR(50),                 -- 插件类型
    game_code        VARCHAR(50),                 -- 关联游戏编码
    file_path        VARCHAR(500),                -- 插件 JAR 路径
    runtime_state    VARCHAR(20),                 -- 运行时状态
    load_time        TIMESTAMP,                   -- 加载时间
    start_time       TIMESTAMP,                   -- 启动时间
    author           VARCHAR(100),
    create_time      TIMESTAMP DEFAULT LOCALTIMESTAMP,
    update_time      TIMESTAMP DEFAULT LOCALTIMESTAMP,
    is_deleted       INTEGER DEFAULT 0,
    remark           TEXT
);

CREATE INDEX IF NOT EXISTS idx_plugin_info_plugin_id ON plugin_info(plugin_id);
CREATE INDEX IF NOT EXISTS idx_plugin_info_status ON plugin_info(status);
CREATE INDEX IF NOT EXISTS idx_plugin_info_is_deleted ON plugin_info(is_deleted);

-- =====================================================
-- 6. 备份记录表 (backup_record)
-- =====================================================
CREATE TABLE IF NOT EXISTS backup_record (
    id             BIGSERIAL PRIMARY KEY,
    instance_id    BIGINT NOT NULL,
    backup_name    VARCHAR(200) NOT NULL,
    backup_type    VARCHAR(20) NOT NULL,          -- FULL-全量, INCREMENTAL-增量
    target_type    VARCHAR(20) NOT NULL,          -- DATABASE-数据库, FILES-文件
    database_type  VARCHAR(20),                   -- MYSQL, POSTGRESQL, SQLITE
    file_size      BIGINT,
    file_path      VARCHAR(500),
    file_md5       VARCHAR(32),
    description    TEXT,
    status         INTEGER DEFAULT 0,             -- 0-备份中, 1-成功, 2-失败
    error_message  TEXT,
    backup_time    TIMESTAMP,
    complete_time  TIMESTAMP,
    progress       INTEGER DEFAULT 0,             -- 0-100
    source_path    VARCHAR(500),
    retry_count    INTEGER DEFAULT 0,
    create_time    TIMESTAMP DEFAULT LOCALTIMESTAMP,
    update_time    TIMESTAMP DEFAULT LOCALTIMESTAMP,
    is_deleted     INTEGER DEFAULT 0,
    remark         TEXT,
    FOREIGN KEY (instance_id) REFERENCES game_instance(id)
);

CREATE INDEX IF NOT EXISTS idx_backup_record_instance_id ON backup_record(instance_id);
CREATE INDEX IF NOT EXISTS idx_backup_record_target_type ON backup_record(target_type);
CREATE INDEX IF NOT EXISTS idx_backup_record_status ON backup_record(status);
CREATE INDEX IF NOT EXISTS idx_backup_record_backup_time ON backup_record(backup_time);
CREATE INDEX IF NOT EXISTS idx_backup_record_is_deleted ON backup_record(is_deleted);

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
    progress         INTEGER DEFAULT 0,           -- 进度百分比 0-100
    progress_message VARCHAR(500),                -- 进度描述
    error_message    TEXT,
    stack_trace      TEXT,
    retry_count      INTEGER DEFAULT 0,
    parent_task_id   VARCHAR(64),
    started_at       TIMESTAMP,
    completed_at     TIMESTAMP,
    duration_ms      BIGINT,
    create_time      TIMESTAMP,
    update_time      TIMESTAMP,
    is_deleted       INTEGER DEFAULT 0,
    remark           TEXT
);

CREATE INDEX IF NOT EXISTS idx_task_record_status ON task_record(status);
CREATE INDEX IF NOT EXISTS idx_task_record_source ON task_record(source);
CREATE INDEX IF NOT EXISTS idx_task_record_task_type ON task_record(task_type);
CREATE INDEX IF NOT EXISTS idx_task_record_scope_key ON task_record(scope_key);
CREATE INDEX IF NOT EXISTS idx_task_record_create_time ON task_record(create_time);
CREATE INDEX IF NOT EXISTS idx_task_record_parent_task_id ON task_record(parent_task_id);

-- =====================================================
-- 8. 任务日志表 (task_log)
-- =====================================================
CREATE TABLE IF NOT EXISTS task_log (
    id          VARCHAR(64) PRIMARY KEY,          -- 雪花ID（应用层生成）
    task_id     VARCHAR(64) NOT NULL,             -- 关联 task_record.id
    level       VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message     TEXT NOT NULL,
    create_time TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_task_log_task_id ON task_log(task_id);
CREATE INDEX IF NOT EXISTS idx_task_log_create_time ON task_log(create_time);

-- =====================================================
-- 9. 定时计划表 (scheduled_task，原 V1.7 迁移建表，现并入建表脚本)
-- =====================================================
CREATE TABLE IF NOT EXISTS scheduled_task (
    id              VARCHAR(64) PRIMARY KEY,      -- 雪花ID（应用层生成）
    name            VARCHAR(200) NOT NULL,        -- 计划名称
    handler_key     VARCHAR(100) NOT NULL,        -- ScheduledTaskHandler 的 key
    cron            VARCHAR(100) NOT NULL,        -- cron 表达式（6 位，Spring 语法，服务器时区）
    payload         TEXT,                         -- payload 模板 JSON
    enabled         INTEGER NOT NULL DEFAULT 1,   -- 用户启用意图: 1-启用 0-禁用
    paused          INTEGER NOT NULL DEFAULT 0,   -- 系统暂停（如插件停用）: 1-暂停
    pause_reason    VARCHAR(500),
    source          VARCHAR(50) NOT NULL,         -- 来源(大写): MAIN / {gameCode}
    plugin_id       VARCHAR(100),                 -- 插件ID（MAIN 来源为 NULL）
    declaration_key VARCHAR(200),                 -- 声明稳定键（pluginId:key）
    user_modified   INTEGER NOT NULL DEFAULT 0,   -- 用户是否改过（声明 upsert 跳过）: 1-是
    create_by       VARCHAR(64),
    update_by       VARCHAR(64),
    create_time     TIMESTAMP,
    update_time     TIMESTAMP,
    is_deleted      INTEGER DEFAULT 0,            -- 逻辑删除 = 声明复活墓碑
    remark          TEXT
);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_source ON scheduled_task(source);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_handler_key ON scheduled_task(handler_key);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_plugin_id ON scheduled_task(plugin_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_declaration_key ON scheduled_task(declaration_key);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_create_time ON scheduled_task(create_time);

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
    progress         INTEGER DEFAULT 0,
    progress_message VARCHAR(500),
    started_at       TIMESTAMP,
    completed_at     TIMESTAMP,
    duration_ms      BIGINT,
    create_time      TIMESTAMP,
    update_time      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_schedule_id ON scheduled_task_run(schedule_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_status ON scheduled_task_run(status);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_create_time ON scheduled_task_run(create_time);

-- =====================================================
-- 11. 定时计划执行日志表 (scheduled_task_run_log)
-- =====================================================
CREATE TABLE IF NOT EXISTS scheduled_task_run_log (
    id          VARCHAR(64) PRIMARY KEY,          -- 雪花ID（应用层生成）
    run_id      VARCHAR(64) NOT NULL,             -- 关联 scheduled_task_run.id
    level       VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message     TEXT NOT NULL,
    create_time TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_log_run_id ON scheduled_task_run_log(run_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_log_create_time ON scheduled_task_run_log(create_time);

-- 系统设置表（SystemController 设置持久化，按 platform/ssh/docker 分组存储 JSON）
CREATE TABLE IF NOT EXISTS sys_setting (
  setting_group VARCHAR(50) PRIMARY KEY,
  setting_value TEXT NOT NULL
);
