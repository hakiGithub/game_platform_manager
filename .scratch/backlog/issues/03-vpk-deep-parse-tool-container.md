# 03: VPK 深度解析移植到工具容器（mission/chapters 提取）

Status: ready-for-agent

## 背景

`/maps/list` 已改为纯目录列举（0.18s，此前每个 vpk 全量 SFTP 下载解析 20s 级）。
地图列表不再需要 vpk 深度信息；但「详情」（missionInfo：标题/章节）与裁剪预检仍依赖
VpkParser 的深度解析，当前走"全量下载到平台→Java 解析"老路径。

## 方向（ADR-0026 延伸）

- 主应用 `HostToolingService` 增加语义方法（如 `inspectVpk(hostId, remoteVpkPath)`）：
  借 platform-tools 容器在主机侧提取 missions/章节信息（容器内 python/脚本报 JSON），
  文件不过平台。
- 插件「详情/裁剪」切换到该能力；`VpkParserService` 缓存保留或退役视届时实现定。
- 需要镜像内置 vpk 解析手段（7z 可解 vpk？或镜像加 python+vpy 模块），先验证可行性再动。

## 验收

- 地图「详情」不再触发全量下载；大 vpk 详情秒级返回。
