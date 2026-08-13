-- =====================================================
-- 数据库迁移脚本 V1.6
-- 说明: 为 host_info 表新增局域网标识字段
-- 执行时机: 应用启动时自动执行或手动执行
-- 关联: ADR-0004 主机局域网标识（isLanHost）引入
-- =====================================================

-- 为 host_info 表新增 is_lan_host 字段
-- SQLite 无 BOOLEAN 类型，存 INTEGER 0/1；MyBatis-Plus 自动映射 Boolean
-- 默认 0（false）：新主机默认按公网处理，平台不会跨公网代劳推送
ALTER TABLE host_info ADD COLUMN is_lan_host BOOLEAN DEFAULT 0;
