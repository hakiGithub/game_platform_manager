-- =====================================================
-- Game Platform Manager 数据库表结构
-- 数据库: SQLite
-- 版本: 1.0.0
-- 创建时间: 2024
-- =====================================================

-- =====================================================
-- 1. 用户表 (sys_user)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    username        VARCHAR(50) NOT NULL UNIQUE,
    password_hash   VARCHAR(128) NOT NULL,
    jwt_secret      VARCHAR(256),
    last_login_time DATETIME,
    last_login_ip   VARCHAR(50),
    create_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted      INTEGER DEFAULT 0,
    remark          TEXT
);

-- 用户表索引
CREATE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username);
CREATE INDEX IF NOT EXISTS idx_sys_user_is_deleted ON sys_user(is_deleted);

-- =====================================================
-- 2. 主机信息表 (host_info)
-- =====================================================
CREATE TABLE IF NOT EXISTS host_info (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
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
    memory_mb        INTEGER,                     -- 内存大小(MB)
    disk_gb          INTEGER,                     -- 磁盘大小(GB)
    cpu_usage        DECIMAL(5,2),
    memory_usage     DECIMAL(5,2),
    disk_usage       DECIMAL(5,2),
    last_check_time  DATETIME,
    create_time      DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time      DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted       INTEGER DEFAULT 0
);

-- 主机表索引
CREATE INDEX IF NOT EXISTS idx_host_info_ip_address ON host_info(ip_address);
CREATE INDEX IF NOT EXISTS idx_host_info_online_status ON host_info(online_status);
CREATE INDEX IF NOT EXISTS idx_host_info_is_deleted ON host_info(is_deleted);

-- =====================================================
-- 3. 游戏元数据表 (game_metadata)
-- =====================================================
CREATE TABLE IF NOT EXISTS game_metadata (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    game_name             VARCHAR(100) NOT NULL,
    game_code             VARCHAR(50) NOT NULL UNIQUE,
    description           TEXT,
    supported_deploy_types TEXT,  -- JSON数组: ["docker", "native"]
    default_port          INTEGER,
    environment_deps      TEXT,   -- JSON对象
    deploy_config         TEXT,   -- JSON对象
    custom_operations     TEXT,   -- JSON对象
    icon_url              VARCHAR(500),
    create_time           DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time           DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted            INTEGER DEFAULT 0,
    remark                TEXT
);

-- 游戏元数据表索引
CREATE INDEX IF NOT EXISTS idx_game_metadata_game_code ON game_metadata(game_code);
CREATE INDEX IF NOT EXISTS idx_game_metadata_is_deleted ON game_metadata(is_deleted);

-- =====================================================
-- 4. 游戏实例表 (game_instance)
-- =====================================================
CREATE TABLE IF NOT EXISTS game_instance (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    instance_name   VARCHAR(100) NOT NULL,
    host_id         INTEGER NOT NULL,
    game_id         INTEGER NOT NULL,
    deploy_type     VARCHAR(20) NOT NULL,  -- docker/native
    port_config     TEXT,                   -- JSON对象
    run_status      INTEGER DEFAULT 0,      -- 0-已停止 1-运行中 2-异常
    online_players  INTEGER DEFAULT 0,
    config_info     TEXT,                   -- JSON对象
    install_path    VARCHAR(500),
    start_command   TEXT,
    stop_command    TEXT,
    database_config TEXT,                   -- JSON对象
    save_path       VARCHAR(500),
    config_path     VARCHAR(500),
    last_backup_time DATETIME,
    runtime_metadata TEXT,                   -- JSON对象，存储运行时元数据
    create_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted      INTEGER DEFAULT 0,
    remark          TEXT,
    FOREIGN KEY (host_id) REFERENCES host_info(id),
    FOREIGN KEY (game_id) REFERENCES game_metadata(id),
    UNIQUE(host_id, instance_name)
);

-- 游戏实例表索引
CREATE INDEX IF NOT EXISTS idx_game_instance_host_id ON game_instance(host_id);
CREATE INDEX IF NOT EXISTS idx_game_instance_game_id ON game_instance(game_id);
CREATE INDEX IF NOT EXISTS idx_game_instance_run_status ON game_instance(run_status);
CREATE INDEX IF NOT EXISTS idx_game_instance_is_deleted ON game_instance(is_deleted);

-- =====================================================
-- 5. 插件信息表 (plugin_info)
-- =====================================================
CREATE TABLE IF NOT EXISTS plugin_info (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    plugin_id        VARCHAR(100) NOT NULL UNIQUE,
    plugin_name      VARCHAR(100) NOT NULL,
    version          VARCHAR(20) NOT NULL,
    status           INTEGER DEFAULT 0,  -- 0-禁用 1-启用
    description      TEXT,
    extension_points TEXT,              -- JSON对象
    config_schema    TEXT,              -- JSON对象
    author           VARCHAR(100),
    create_time      DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time      DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted       INTEGER DEFAULT 0,
    remark           TEXT
);

-- 插件信息表索引
CREATE INDEX IF NOT EXISTS idx_plugin_info_plugin_id ON plugin_info(plugin_id);
CREATE INDEX IF NOT EXISTS idx_plugin_info_status ON plugin_info(status);
CREATE INDEX IF NOT EXISTS idx_plugin_info_is_deleted ON plugin_info(is_deleted);

-- =====================================================
-- 6. 操作日志表 (operation_log)
-- =====================================================
CREATE TABLE IF NOT EXISTS operation_log (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    operator          VARCHAR(50) NOT NULL,
    operation_type    VARCHAR(50) NOT NULL,
    operation_target  VARCHAR(100),
    operation_content TEXT,
    operation_result  VARCHAR(20),  -- success/fail
    ip_address        VARCHAR(50),
    error_message     TEXT,
    create_time       DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time       DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted        INTEGER DEFAULT 0,
    remark            TEXT
);

-- 操作日志表索引
CREATE INDEX IF NOT EXISTS idx_operation_log_operator ON operation_log(operator);
CREATE INDEX IF NOT EXISTS idx_operation_log_operation_type ON operation_log(operation_type);
CREATE INDEX IF NOT EXISTS idx_operation_log_create_time ON operation_log(create_time);
CREATE INDEX IF NOT EXISTS idx_operation_log_is_deleted ON operation_log(is_deleted);

-- =====================================================
-- 7. 备份记录表 (backup_record)
-- =====================================================
CREATE TABLE IF NOT EXISTS backup_record (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    instance_id     INTEGER NOT NULL,
    backup_name     VARCHAR(200) NOT NULL,
    backup_type     VARCHAR(20) NOT NULL,  -- FULL-全量, INCREMENTAL-增量
    target_type     VARCHAR(20) NOT NULL,  -- DATABASE-数据库, FILES-文件
    database_type   VARCHAR(20),           -- MYSQL, POSTGRESQL, SQLITE
    file_size       BIGINT,
    file_path       VARCHAR(500),
    file_md5        VARCHAR(32),
    description     TEXT,
    status          INTEGER DEFAULT 0,     -- 0-备份中, 1-成功, 2-失败
    error_message   TEXT,
    backup_time     DATETIME,
    complete_time   DATETIME,
    progress        INTEGER DEFAULT 0,     -- 0-100
    source_path     VARCHAR(500),
    retry_count     INTEGER DEFAULT 0,
    create_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted      INTEGER DEFAULT 0,
    remark          TEXT,
    FOREIGN KEY (instance_id) REFERENCES game_instance(id)
);

-- 备份记录表索引
CREATE INDEX IF NOT EXISTS idx_backup_record_instance_id ON backup_record(instance_id);
CREATE INDEX IF NOT EXISTS idx_backup_record_target_type ON backup_record(target_type);
CREATE INDEX IF NOT EXISTS idx_backup_record_status ON backup_record(status);
CREATE INDEX IF NOT EXISTS idx_backup_record_backup_time ON backup_record(backup_time);
CREATE INDEX IF NOT EXISTS idx_backup_record_is_deleted ON backup_record(is_deleted);
