# 03: Minecraft 家族调研（mc / mcb / pmc / wmc / vpmc）

Status: ready-for-agent
Type: research
Blocked by: 01

## 范围

gameCode：`minecraft`（Java 版）、`mcb`（Bedrock）、`pmc`（PaperMC）、`wmc`（WaterfallMC 代理）、
`vpmc`（Velocity 代理）。

## 调研问题

1. `itzg/docker-minecraft-server` 暴露了哪些可在平台上复用的能力面：版本切换、模组加载器
   （Forge/Fabric/Quilt/Paper）、modpack 平台（CurseForge/Modrinth/FTB）自动安装、世界/存档管理、
   `RCON` 开关、健康检查？
2. MCSManager 的分布式模型（Daemon + Web）有哪些能力是平台还没有的：实例分布式调度、终端、
   文件在线编辑、计划任务、镜像/模板、用户与权限、API？给出源码/文档路径。
3. Minecraft 服务端的管理协议面有哪些：RCON（`rcon-cli`）、`Query`、Minecraft 1.19+ 的
   `server.properties` 管理、Bedrock 的 `bedrock_server` 无 RCON 时怎么办？
4. 世界/存档管理：备份与回滚、地图渲染（Minecraft-Overviewer）、玩家数据（`.dat`/NBT）查看编辑，
   哪些有成熟工具？这些是"文件层能力"还是"协议层能力"？
5. 代理端（Waterfall/Velocity）的运维操作：后端服务器注册、玩家跨服转移、插件（BungeeCord 系）
   管理，是否值得单独做一个插件？

## 输出

`.scratch/plugin-capability-mining/research/02-minecraft-family.md`，同样按
"能力名 / 上游实现 + 出处 / 契约（协议·文件·命令） / 平台可复用方式"四列组织。

## 验收

- [ ] 至少 3 个已核验上游项目（itzg 镜像、MCSManager、Overviewer 已核验，仍需补 Modrinth/CurseForge 相关）。
- [ ] 明确区分"宿主层能力"（文件/容器/SSH，平台已有）与"插件语义层能力"（版本/模组/世界，需要新做）。
- [ ] 明确 Bedrock 与 Java 版在管理协议上的差异对插件设计的影响。
