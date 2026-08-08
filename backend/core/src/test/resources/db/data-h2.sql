-- H2数据库测试数据初始化脚本

-- 插入测试用户 (密码: admin123 的SHA256哈希值)
INSERT INTO sys_user (username, password_hash, remark) 
VALUES ('admin', '240be518fabd2724ddb6f04eeb9d5b044f59fc4a1e7d4b1e6e5dc4e5e5d5c5a5', '管理员账号');

-- 插入测试游戏
INSERT INTO game_metadata (game_name, game_code, description, supported_deploy_types, default_port) 
VALUES ('Minecraft', 'minecraft', 'Minecraft游戏服务器', '["docker","native"]', 25565);

INSERT INTO game_metadata (game_name, game_code, description, supported_deploy_types, default_port) 
VALUES ('Valheim', 'valheim', '英灵神殿服务器', '["docker","native"]', 2456);

-- 插入测试主机
INSERT INTO host_info (host_name, ip_address, ssh_port, ssh_user, online_status) 
VALUES ('测试服务器1', '192.168.1.100', 22, 'root', 1);

INSERT INTO host_info (host_name, ip_address, ssh_port, ssh_user, online_status) 
VALUES ('测试服务器2', '192.168.1.101', 22, 'root', 0);
