-- V1.8: RCON 标准键归一化（ADR-0016 决策 4）
-- 背景：RCON 传输层上提主应用后，端点解析只认标准键 configInfo.rconPassword；
--      游戏专属键（L4D2_RCON_PASSWORD / SRCDS_RCONPW）不再被主应用识别。
--      本脚本把存量实例的专属键密码归一化写入标准键（标准键已存在则不动）。
-- 仅 SQLite 生效（ADR-0015：db/migration 迁移体系仅对 SQLite 生效）。

UPDATE game_instance
SET config_info = json_set(
        config_info,
        '$.rconPassword',
        COALESCE(
            json_extract(config_info, '$.L4D2_RCON_PASSWORD'),
            json_extract(config_info, '$.SRCDS_RCONPW')))
WHERE config_info IS NOT NULL
  AND json_valid(config_info)
  AND json_extract(config_info, '$.rconPassword') IS NULL
  AND (json_extract(config_info, '$.L4D2_RCON_PASSWORD') IS NOT NULL
       OR json_extract(config_info, '$.SRCDS_RCONPW') IS NOT NULL);
