-- =====================================================
-- Migration: V1.5
-- Description: 任务中心模块表（task_record + task_log）
-- Date: 2026-08-02
--
-- 设计说明（ADR-028）:
--   - 不使用 SQLite 特有的 datetime('now', 'localtime') 默认值
--   - create_time / update_time 由 MyBatis-Plus MetaObjectHandler 自动填充
--   - TIMESTAMP 类型在 SQLite/MySQL/PostgreSQL 均可用
--   - 主键使用雪花 ID（String），由应用层 ExtensionIdGenerator 生成
-- =====================================================

-- =====================================================
-- 1. 任务记录表 (task_record)
-- 用途: 记录任务提交、执行、状态、结果等全生命周期数据
-- =====================================================
CREATE TABLE IF NOT EXISTS task_record (
    id                  VARCHAR(64) PRIMARY KEY,              -- 雪花ID（应用层生成）
    task_type           VARCHAR(100) NOT NULL,                -- 任务类型: crawl/deploy/backup/restart/export
    source              VARCHAR(50) NOT NULL,                -- 任务来源(大写): MAIN / L4D2 / {gameCode}
    status              VARCHAR(20) NOT NULL,                 -- 状态: PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
    submitter           VARCHAR(50),                          -- 提交者用户名或 SYSTEM
    scope_type          VARCHAR(50) DEFAULT 'GLOBAL',         -- 作用域: INSTANCE/HOST/GLOBAL
    scope_key           VARCHAR(100),                         -- 作用域键，如 instanceId=55
    scope_name          VARCHAR(200),                         -- 作用域名称，如实例名
    payload             TEXT,                                 -- 输入参数 JSON（上限 64KB，应用层校验）
    result              TEXT,                                 -- 输出结果 JSON（上限 256KB，应用层校验）
    result_summary      VARCHAR(500),                         -- Handler 生成的结果摘要（列表页展示）
    progress            INTEGER DEFAULT 0,                    -- 进度百分比 0-100
    progress_message    VARCHAR(500),                         -- 进度描述，如 "已处理 20/40 页"
    error_message       TEXT,                                 -- 失败时的错误信息
    stack_trace         TEXT,                                 -- 失败时的堆栈（详情页可折叠展示）
    retry_count         INTEGER DEFAULT 0,                    -- 已重试次数
    parent_task_id      VARCHAR(64),                          -- 重试时关联的原任务ID
    started_at          TIMESTAMP,                            -- 开始执行时间（应用层填充）
    completed_at        TIMESTAMP,                            -- 完成/失败/取消时间（应用层填充）
    duration_ms         BIGINT,                              -- 耗时毫秒
    create_time         TIMESTAMP,                           -- 创建时间（MetaObjectHandler 填充）
    update_time         TIMESTAMP,                           -- 更新时间（MetaObjectHandler 填充）
    is_deleted          INTEGER DEFAULT 0,                    -- 逻辑删除: 0-未删除 1-已删除
    remark              TEXT
);

CREATE INDEX IF NOT EXISTS idx_task_record_status ON task_record(status);
CREATE INDEX IF NOT EXISTS idx_task_record_source ON task_record(source);
CREATE INDEX IF NOT EXISTS idx_task_record_task_type ON task_record(task_type);
CREATE INDEX IF NOT EXISTS idx_task_record_scope_key ON task_record(scope_key);
CREATE INDEX IF NOT EXISTS idx_task_record_create_time ON task_record(create_time);
CREATE INDEX IF NOT EXISTS idx_task_record_parent_task_id ON task_record(parent_task_id);

-- =====================================================
-- 2. 任务日志表 (task_log)
-- 用途: 与 task_record 1:N 关联，记录任务执行过程中的细粒度日志
-- 保留策略: 每个任务最多保留 500 条，超出按时间倒序保留最新 500 条
-- =====================================================
CREATE TABLE IF NOT EXISTS task_log (
    id                  VARCHAR(64) PRIMARY KEY,              -- 雪花ID（应用层生成）
    task_id             VARCHAR(64) NOT NULL,                -- 关联 task_record.id
    level               VARCHAR(20) NOT NULL DEFAULT 'INFO', -- 日志级别: INFO/WARN/ERROR
    message             TEXT NOT NULL,                       -- 日志消息
    create_time         TIMESTAMP                           -- 创建时间（应用层填充）
);

CREATE INDEX IF NOT EXISTS idx_task_log_task_id ON task_log(task_id);
CREATE INDEX IF NOT EXISTS idx_task_log_create_time ON task_log(create_time);
