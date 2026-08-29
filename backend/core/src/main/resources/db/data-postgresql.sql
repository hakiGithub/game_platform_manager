-- =====================================================
-- Game Platform Manager 初始化数据
-- 数据库: PostgreSQL 12+
-- 版本: 1.1.0（ADR-0015 多方言拆分）
--
-- 说明:
-- 1. 仅在表不存在（全新库）时由 DatabaseInitializer 执行一次。
-- 2. ON CONFLICT DO NOTHING 防重：即使重复执行也不会因 UNIQUE 冲突中断启动。
-- 3. 默认管理员账号: admin / admin123（SHA256 加密后存储）
-- =====================================================

-- 1. 初始化管理员用户
INSERT INTO sys_user (username, password_hash, jwt_secret, remark)
VALUES ('admin', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', NULL, '系统管理员')
ON CONFLICT DO NOTHING;

-- 2. 初始化游戏元数据
INSERT INTO game_metadata (game_name, game_code, description, supported_deploy_types, default_port, environment_deps, deploy_config, custom_operations, remark)
VALUES
('Minecraft', 'minecraft',
 'Minecraft是一款沙盒游戏，玩家可以在一个由方块构成的三维世界中自由探索、建造和生存。',
 '["docker", "native"]', 25565,
 '{"java": "17+", "memory": "2G+"}',
 '{"docker": {"image": "itzg/minecraft-server", "ports": ["25565:25565"], "volumes": ["minecraft-data:/data"]}, "native": {"start_script": "start.sh", "stop_script": "stop.sh"}}',
 '{"backup": {"command": "backup", "description": "备份游戏数据"}, "restore": {"command": "restore", "description": "恢复游戏数据"}}',
 'Minecraft服务器'),
('Terraria', 'terraria',
 'Terraria是一款2D沙盒游戏，融合了探索、建造、战斗和生存元素。',
 '["docker", "native"]', 7777,
 '{"dotnet": "6.0+", "memory": "1G+"}',
 '{"docker": {"image": "ryshe/terraria", "ports": ["7777:7777"], "volumes": ["terraria-data:/data"]}, "native": {"start_script": "start.sh", "stop_script": "stop.sh"}}',
 '{"backup": {"command": "backup", "description": "备份游戏数据"}}',
 'Terraria服务器'),
('Valheim', 'valheim',
 'Valheim是一款北欧神话主题的生存游戏，支持多人联机。',
 '["docker", "native"]', 2456,
 '{"steamcmd": "required", "memory": "4G+"}',
 '{"docker": {"image": "lloesche/valheim-server", "ports": ["2456-2458:2456-2458"], "volumes": ["valheim-data:/config"]}, "native": {"start_script": "start_server.sh"}}',
 '{"backup": {"command": "backup", "description": "备份游戏数据"}}',
 'Valheim服务器'),
('Don''t Starve Together', 'dst',
 '饥荒联机版是一款生存冒险游戏，支持多人合作。',
 '["docker", "native"]', 10999,
 '{"steamcmd": "required", "memory": "2G+"}',
 '{"docker": {"image": "dst-acid/dst-server", "ports": ["10999-11000:10999-11000"], "volumes": ["dst-data:/data"]}, "native": {"start_script": "start_server.sh"}}',
 '{"backup": {"command": "backup", "description": "备份游戏数据"}}',
 '饥荒联机版服务器')
ON CONFLICT DO NOTHING;

-- 3. 初始化插件信息
INSERT INTO plugin_info (plugin_id, plugin_name, version, status, description, extension_points, config_schema, author, remark)
VALUES
('auto-backup', '自动备份插件', '1.0.0', 1,
 '定时自动备份游戏服务器数据，支持多种备份策略。',
 '{"backup": {"interface": "com.gameplatform.plugin.BackupExtension", "methods": ["execute", "schedule"]}}',
 '{"schedule": {"type": "cron", "default": "0 0 3 * * ?"}, "retention": {"type": "number", "default": 7}, "compression": {"type": "boolean", "default": true}}',
 'GamePlatform', '自动备份插件'),
('performance-monitor', '性能监控插件', '1.0.0', 1,
 '实时监控服务器性能指标，包括CPU、内存、网络等。',
 '{"monitor": {"interface": "com.gameplatform.plugin.MonitorExtension", "methods": ["collect", "report"]}}',
 '{"interval": {"type": "number", "default": 60}, "threshold": {"cpu": {"type": "number", "default": 80}, "memory": {"type": "number", "default": 85}}}',
 'GamePlatform', '性能监控插件'),
('player-manager', '玩家管理插件', '1.0.0', 0,
 '管理玩家权限、封禁、白名单等功能。',
 '{"player": {"interface": "com.gameplatform.plugin.PlayerExtension", "methods": ["ban", "unban", "whitelist", "kick"]}}',
 '{"autoKick": {"type": "boolean", "default": false}, "maxPlayers": {"type": "number", "default": 20}}',
 'GamePlatform', '玩家管理插件')
ON CONFLICT DO NOTHING;
