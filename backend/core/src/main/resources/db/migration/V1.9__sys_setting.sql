-- V1.9: 系统设置持久化表（修复系统设置假回环，见 .scratch/backlog/issues/03）
CREATE TABLE IF NOT EXISTS sys_setting (
  setting_group VARCHAR(50) PRIMARY KEY,
  setting_value TEXT NOT NULL
);
