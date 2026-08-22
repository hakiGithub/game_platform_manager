-- =====================================================
-- Migration: V1.7
-- Description: 定时任务管理模块表（scheduled_task + scheduled_task_run + scheduled_task_run_log）
-- Date: 2026-08-22
--
-- 设计说明（ADR-0011）:
--   - 独立于任务中心 task_record 模型，不向执行队列提交任务
--   - 来源隔离镜像 task_record 模式（source / plugin_id 字段）
--   - 主键使用雪花 ID（String），由应用层 ExtensionIdGenerator 生成
--   - create_time / update_time 由 MyBatis-Plus MetaObjectHandler 自动填充
--   - scheduled_task 用逻辑删除（is_deleted）作为声明式计划的复活墓碑：
--     用户删除的计划，插件重载 upsert 时检测到墓碑不再复活
-- =====================================================

-- =====================================================
-- 1. 定时计划表 (scheduled_task)
-- 用途: 计划定义（cron + handler key + payload 模板 + enabled）
-- =====================================================
CREATE TABLE IF NOT EXISTS scheduled_task (
    id                  VARCHAR(64) PRIMARY KEY,              -- 雪花ID（应用层生成）
    name                VARCHAR(200) NOT NULL,                -- 计划名称
    handler_key         VARCHAR(100) NOT NULL,                -- ScheduledTaskHandler 的 key
    cron                VARCHAR(100) NOT NULL,                -- cron 表达式（6 位，Spring 语法，服务器时区）
    payload             TEXT,                                 -- payload 模板 JSON
    enabled             INTEGER NOT NULL DEFAULT 1,           -- 用户启用意图: 1-启用 0-禁用
    paused              INTEGER NOT NULL DEFAULT 0,           -- 系统暂停（如插件停用）: 1-暂停
    pause_reason        VARCHAR(500),                         -- 暂停原因
    source              VARCHAR(50) NOT NULL,                 -- 来源(大写): MAIN / {gameCode}
    plugin_id           VARCHAR(100),                         -- 插件ID（MAIN 来源为 NULL）
    declaration_key     VARCHAR(200),                         -- 声明稳定键（pluginId:key，声明式计划才有）
    user_modified       INTEGER NOT NULL DEFAULT 0,           -- 用户是否改过（声明 upsert 跳过）: 1-是
    create_by           VARCHAR(64),
    update_by           VARCHAR(64),
    create_time         TIMESTAMP,
    update_time         TIMESTAMP,
    is_deleted          INTEGER DEFAULT 0,                    -- 逻辑删除 = 声明复活墓碑
    remark              TEXT
);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_source ON scheduled_task(source);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_handler_key ON scheduled_task(handler_key);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_plugin_id ON scheduled_task(plugin_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_declaration_key ON scheduled_task(declaration_key);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_create_time ON scheduled_task(create_time);

-- =====================================================
-- 2. 触发记录表 (scheduled_task_run)
-- 用途: 每次到点（或手动触发）产生的一次执行记录
-- 状态机: RUNNING → SUCCEEDED / FAILED / CANCELLED / SKIPPED（终态不可变）
-- 保留策略: 30 天（ScheduledTaskCleanupScheduler 物理删除）
-- =====================================================
CREATE TABLE IF NOT EXISTS scheduled_task_run (
    id                  VARCHAR(64) PRIMARY KEY,              -- 雪花ID（应用层生成，即 runId）
    schedule_id         VARCHAR(64) NOT NULL,                 -- 关联 scheduled_task.id
    schedule_name       VARCHAR(200),                         -- 计划名称快照（计划删除后仍可读）
    trigger_type        VARCHAR(20) NOT NULL,                 -- 触发方式: CRON / MANUAL
    status              VARCHAR(20) NOT NULL,                 -- RUNNING / SUCCEEDED / FAILED / CANCELLED / SKIPPED
    payload             TEXT,                                 -- 本次执行的 payload 快照
    result              TEXT,                                 -- 输出结果 JSON
    error_message       TEXT,                                 -- SKIPPED 原因 / 失败错误信息
    progress            INTEGER DEFAULT 0,                    -- 进度百分比 0-100
    progress_message    VARCHAR(500),                         -- 进度描述
    started_at          TIMESTAMP,                            -- 开始执行时间
    completed_at        TIMESTAMP,                            -- 结束时间
    duration_ms         BIGINT,                               -- 耗时毫秒
    create_time         TIMESTAMP,
    update_time         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_schedule_id ON scheduled_task_run(schedule_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_status ON scheduled_task_run(status);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_create_time ON scheduled_task_run(create_time);

-- =====================================================
-- 3. 触发日志表 (scheduled_task_run_log)
-- 用途: 与 scheduled_task_run 1:N 关联，Handler 执行日志
-- 保留策略: 每 run 最多 500 条，随 run 30 天清理
-- =====================================================
CREATE TABLE IF NOT EXISTS scheduled_task_run_log (
    id                  VARCHAR(64) PRIMARY KEY,              -- 雪花ID（应用层生成）
    run_id              VARCHAR(64) NOT NULL,                 -- 关联 scheduled_task_run.id
    level               VARCHAR(20) NOT NULL DEFAULT 'INFO', -- 日志级别: INFO/WARN/ERROR
    message             TEXT NOT NULL,                        -- 日志消息
    create_time         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_log_run_id ON scheduled_task_run_log(run_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_log_create_time ON scheduled_task_run_log(create_time);
