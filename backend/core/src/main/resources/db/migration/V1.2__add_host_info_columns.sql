-- =====================================================
-- 数据库迁移脚本 V1.2
-- 说明: 为 host_info 表添加新字段
-- 执行时机: 应用启动时自动执行或手动执行
-- =====================================================

-- 为 host_info 表添加新字段
ALTER TABLE host_info ADD COLUMN ssh_password TEXT;
ALTER TABLE host_info ADD COLUMN tags TEXT;
ALTER TABLE host_info ADD COLUMN os_type VARCHAR(50);
ALTER TABLE host_info ADD COLUMN os_version VARCHAR(100);
ALTER TABLE host_info ADD COLUMN cpu_cores INTEGER;
ALTER TABLE host_info ADD COLUMN memory_mb INTEGER;
ALTER TABLE host_info ADD COLUMN disk_gb INTEGER;
