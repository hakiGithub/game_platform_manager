-- =====================================================
-- V1.11: API 令牌表 (api_token) — ADR-0029 长期可吊销凭证
-- 存量 SQLite 库升级建表；内容与 schema-sqlite.sql 的 api_token 段落逐字符一致，
-- 保证「新库走 schema、老库走迁移」两条路径产出完全相同的结构。
-- =====================================================
CREATE TABLE IF NOT EXISTS api_token (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         VARCHAR(100) NOT NULL,
    token_prefix VARCHAR(16)  NOT NULL,
    token_hash   VARCHAR(128) NOT NULL,
    scope        VARCHAR(20)  NOT NULL DEFAULT 'read',
    user_id      INTEGER      NOT NULL,
    expires_at   DATETIME,
    revoked      INTEGER      NOT NULL DEFAULT 0,
    revoked_at   DATETIME,
    last_used_at DATETIME,
    create_time  DATETIME DEFAULT (datetime('now', 'localtime')),
    -- 刻意不建 trg_api_token_update_time：节流写 last_used_at 不得刷新 update_time（ADR-0029 D6）
    update_time  DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted   INTEGER DEFAULT 0,
    remark       TEXT,
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_api_token_hash ON api_token(token_hash);
CREATE INDEX IF NOT EXISTS idx_api_token_user_id ON api_token(user_id);
CREATE INDEX IF NOT EXISTS idx_api_token_is_deleted ON api_token(is_deleted);
