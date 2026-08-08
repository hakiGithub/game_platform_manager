-- =====================================================
-- Game Platform Manager 数据库迁移脚本
-- 版本: 1.1.0
-- 描述: 添加插件框架相关字段
-- =====================================================

-- =====================================================
-- 1. 扩展 plugin_info 表
-- =====================================================

-- 添加插件类型字段
ALTER TABLE plugin_info ADD COLUMN plugin_type VARCHAR(50) DEFAULT 'game_enhancement';

-- 添加游戏编码字段（用于关联游戏）
ALTER TABLE plugin_info ADD COLUMN game_code VARCHAR(50);

-- 添加插件文件路径字段
ALTER TABLE plugin_info ADD COLUMN file_path VARCHAR(500);

-- 添加插件状态字段（运行时状态）
ALTER TABLE plugin_info ADD COLUMN runtime_state VARCHAR(20) DEFAULT 'STOPPED';

-- 添加加载时间字段
ALTER TABLE plugin_info ADD COLUMN load_time DATETIME;

-- 添加启动时间字段
ALTER TABLE plugin_info ADD COLUMN start_time DATETIME;

-- 创建游戏编码索引
CREATE INDEX IF NOT EXISTS idx_plugin_info_game_code ON plugin_info(game_code);

-- 创建插件类型索引
CREATE INDEX IF NOT EXISTS idx_plugin_info_plugin_type ON plugin_info(plugin_type);

-- =====================================================
-- 2. 扩展 game_instance 表
-- =====================================================

-- 添加游戏编码字段（冗余字段，便于查询）
ALTER TABLE game_instance ADD COLUMN game_code VARCHAR(50);

-- 创建游戏编码索引
CREATE INDEX IF NOT EXISTS idx_game_instance_game_code ON game_instance(game_code);

-- =====================================================
-- 3. 更新说明
-- =====================================================
-- plugin_type: 插件类型
--   - game_enhancement: 游戏增强插件（默认）
--   - system: 系统插件
--   - integration: 集成插件
--
-- game_code: 游戏编码
--   - 用于关联游戏元数据和插件
--   - 例如: l4d2, minecraft, palworld
--
-- file_path: 插件文件路径
--   - 插件JAR包的完整路径
--
-- runtime_state: 运行时状态
--   - CREATED: 已创建
--   - DISABLED: 已禁用
--   - RESOLVED: 已解析
--   - STARTED: 已启动
--   - STOPPED: 已停止
