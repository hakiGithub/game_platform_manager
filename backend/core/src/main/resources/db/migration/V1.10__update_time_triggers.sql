-- V1.10: update_time 自动刷新触发器（与 schema-mysql 的 ON UPDATE 语义对齐）
CREATE TRIGGER IF NOT EXISTS trg_host_info_update_time
AFTER UPDATE ON host_info
BEGIN
  UPDATE host_info SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_game_instance_update_time
AFTER UPDATE ON game_instance
BEGIN
  UPDATE game_instance SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_plugin_info_update_time
AFTER UPDATE ON plugin_info
BEGIN
  UPDATE plugin_info SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_backup_record_update_time
AFTER UPDATE ON backup_record
BEGIN
  UPDATE backup_record SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
