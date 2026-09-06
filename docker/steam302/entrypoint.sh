#!/bin/sh
# Steamcommunity 302 容器入口：
# - 二进制无条件从镜像刷新到 /data（镜像升级即更新二进制）
# - 配置文件仅首装种子（不覆盖用户修改）
# - 工作目录 /data（卷挂载），日志/证书/Caddyfile 等运行时产物都落在宿主机
set -e
mkdir -p /data

cp -f /opt/s302/steamcommunity_302.cli /opt/s302/steamcommunity_302.caddy /data/
chmod +x /data/steamcommunity_302.cli /data/steamcommunity_302.caddy

# 配置首装种子（dns_hosts/dns_blacklist 由 CLI 首次启动自动生成，无需播种）
for f in S302.ini S302_rules.ini S302.hosts; do
    [ -f "/data/$f" ] || cp "/opt/s302/$f" "/data/$f"
done

cd /data
exec ./steamcommunity_302.cli
