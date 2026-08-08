-- =====================================================
-- Migration: V1.3
-- Description: 添加 Docker 容器关联表
-- Date: 2026-03-24
-- =====================================================

-- 创建 Docker 容器关联表
CREATE TABLE IF NOT EXISTS docker_container_link (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id         INTEGER NOT NULL,
    container_id    VARCHAR(64) NOT NULL,
    container_name  VARCHAR(255) NOT NULL,
    instance_id     INTEGER,
    link_type       VARCHAR(20) NOT NULL DEFAULT 'host',
    image_name      VARCHAR(255),
    image_tag       VARCHAR(100),
    auto_linked     INTEGER NOT NULL DEFAULT 0,
    create_by       INTEGER,
    create_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted      INTEGER NOT NULL DEFAULT 0,
    remark          TEXT,
    CONSTRAINT uk_dcl_host_container UNIQUE (host_id, container_id)
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_dcl_host_id ON docker_container_link(host_id);
CREATE INDEX IF NOT EXISTS idx_dcl_container_id ON docker_container_link(container_id);
CREATE INDEX IF NOT EXISTS idx_dcl_instance_id ON docker_container_link(instance_id);
CREATE INDEX IF NOT EXISTS idx_dcl_link_type ON docker_container_link(link_type);
CREATE INDEX IF NOT EXISTS idx_dcl_create_by ON docker_container_link(create_by);
CREATE INDEX IF NOT EXISTS idx_dcl_is_deleted ON docker_container_link(is_deleted);
