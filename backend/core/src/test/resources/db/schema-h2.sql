-- H2数据库测试初始化脚本
-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    jwt_secret VARCHAR(255),
    last_login_time TIMESTAMP,
    last_login_ip VARCHAR(50),
    remark VARCHAR(500),
    deleted TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 主机信息表
CREATE TABLE IF NOT EXISTS host_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    host_name VARCHAR(100) NOT NULL,
    ip_address VARCHAR(50) NOT NULL UNIQUE,
    ssh_port INT DEFAULT 22,
    ssh_user VARCHAR(50),
    ssh_private_key TEXT,
    online_status TINYINT DEFAULT 0,
    cpu_usage DECIMAL(5,2),
    memory_usage DECIMAL(5,2),
    disk_usage DECIMAL(5,2),
    last_check_time TIMESTAMP,
    remark VARCHAR(500),
    deleted TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 游戏元数据表
CREATE TABLE IF NOT EXISTS game_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_name VARCHAR(100) NOT NULL,
    game_code VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(500),
    supported_deploy_types TEXT,
    default_port INT,
    environment_deps TEXT,
    deploy_config TEXT,
    custom_operations TEXT,
    icon_url VARCHAR(255),
    remark VARCHAR(500),
    deleted TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 游戏实例表
CREATE TABLE IF NOT EXISTS game_instance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_name VARCHAR(100) NOT NULL UNIQUE,
    host_id BIGINT NOT NULL,
    game_id BIGINT NOT NULL,
    deploy_type VARCHAR(20),
    port_config TEXT,
    run_status TINYINT DEFAULT 0,
    online_players INT DEFAULT 0,
    config_info TEXT,
    install_path VARCHAR(255),
    start_command TEXT,
    stop_command TEXT,
    remark VARCHAR(500),
    deleted TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 插件信息表
CREATE TABLE IF NOT EXISTS plugin_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plugin_id VARCHAR(100) NOT NULL UNIQUE,
    plugin_name VARCHAR(100) NOT NULL,
    version VARCHAR(50),
    status TINYINT DEFAULT 0,
    description VARCHAR(500),
    extension_points TEXT,
    config_schema TEXT,
    author VARCHAR(50),
    remark VARCHAR(500),
    deleted TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator VARCHAR(50),
    operation_type VARCHAR(50),
    operation_target VARCHAR(50),
    operation_content VARCHAR(500),
    operation_result VARCHAR(20),
    ip_address VARCHAR(50),
    error_message TEXT,
    deleted TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- 任务中心模块表（task_record + task_log）
-- 与生产 schema V1.5__task_center.sql 保持一致
-- =====================================================

-- 任务记录表
CREATE TABLE IF NOT EXISTS task_record (
    id                  VARCHAR(64) PRIMARY KEY,
    task_type           VARCHAR(100) NOT NULL,
    source              VARCHAR(50) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    submitter           VARCHAR(50),
    scope_type          VARCHAR(50) DEFAULT 'GLOBAL',
    scope_key           VARCHAR(100),
    scope_name          VARCHAR(200),
    payload             TEXT,
    result              TEXT,
    result_summary      VARCHAR(500),
    progress            INTEGER DEFAULT 0,
    progress_message    VARCHAR(500),
    error_message       TEXT,
    stack_trace         TEXT,
    retry_count         INTEGER DEFAULT 0,
    parent_task_id      VARCHAR(64),
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    duration_ms         BIGINT,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted          INTEGER DEFAULT 0,
    remark              TEXT
);

CREATE INDEX IF NOT EXISTS idx_task_record_status ON task_record(status);
CREATE INDEX IF NOT EXISTS idx_task_record_source ON task_record(source);
CREATE INDEX IF NOT EXISTS idx_task_record_task_type ON task_record(task_type);
CREATE INDEX IF NOT EXISTS idx_task_record_scope_key ON task_record(scope_key);
CREATE INDEX IF NOT EXISTS idx_task_record_create_time ON task_record(create_time);
CREATE INDEX IF NOT EXISTS idx_task_record_parent_task_id ON task_record(parent_task_id);

-- 任务日志表
CREATE TABLE IF NOT EXISTS task_log (
    id                  VARCHAR(64) PRIMARY KEY,
    task_id             VARCHAR(64) NOT NULL,
    level               VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message             TEXT NOT NULL,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_task_log_task_id ON task_log(task_id);
CREATE INDEX IF NOT EXISTS idx_task_log_create_time ON task_log(create_time);
