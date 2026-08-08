-- =====================================================
-- 数据库迁移脚本 V1.1
-- 说明: 添加缺失的列
-- 执行时机: 当遇到 "no such column" 错误时手动执行
-- =====================================================

-- 1. operation_log 表添加 remark 列
ALTER TABLE operation_log ADD COLUMN remark TEXT;

-- 2. game_instance 表添加缺失的列
ALTER TABLE game_instance ADD COLUMN database_config TEXT;
ALTER TABLE game_instance ADD COLUMN save_path VARCHAR(500);
ALTER TABLE game_instance ADD COLUMN config_path VARCHAR(500);
ALTER TABLE game_instance ADD COLUMN last_backup_time DATETIME;
