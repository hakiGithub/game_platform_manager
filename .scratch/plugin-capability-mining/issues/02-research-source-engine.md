# 02: Source 引擎生态调研（SourceMod / SourceBans / RCON 语义）

Status: ready-for-agent
Type: research
Blocked by: 01

## 范围

gameCode：`l4d2` `l4d` `css` `tf2` `tf2c` `tfc` `csgo` `cs2` `cs` `cscz` `dods` `dod` `hl2dm` `hldm` `hldms`
`gmod` `ins` `inss` `doi` `nmrih` `zps` `zmr` `sven` `bmdm` `ahl` `ahl2` `fof` `pvkii` `em` `sfc` `dys`
`dab` `bs` `ts` `sof2` `q2`(非Source,略) —— 重点是 Source 引擎那批。

## 调研问题

1. SourceMod 的 admin 体系（`admins_simple.ini` / `admins.cfg`、admin 组、免疫等级、override）是什么结构？
   它的权限模型能否映射到平台现有的"操作审计 + 实例级操作"？给出源码/文档路径。
2. SourceBans++ 的 Web 端提供哪些能力（封禁、禁言、管理员、申诉、服务器组、提交日志）？数据落在哪种 DB？
   是否有可调用的 API 而非直接改库？
3. Source 系通用的 RCON 命令语义清单（`status` `kick` `banid` `changelevel` `sv_cheats` `cvar` 读写、
   `sm_` 系列）哪些适合做成平台上的"自定义操作 + 语义层"？
4. MetaMod/SourceMod 插件的安装-启用-禁用-配置链路在文件系统上具体落哪些目录
   （`addons/sourcemod/plugins`、`cfg/sourcemod/*.cfg`、`disabled/`）？与 L4D2 插件现有实现差异。
5. 蒸汽创意工坊（Workshop）/ 第三方地图的管理在 Source 系如何统一（collection id、addon 列表、
   `host_workshop_map`）？

## 输出

`.scratch/plugin-capability-mining/research/01-source-engine.md`，按"能力名 / 上游实现 + 出处 /
文件系统或协议契约 / 平台可复用方式"四列组织。

## 验收

- [ ] 至少 3 个已核验上游项目，每条能力有源码路径或官方 wiki 链接。
- [ ] 明确列出哪些能力已被 `plugin-l4d2` 实现、哪些是新增（避免重复造）。
- [ ] 明确 Source 系是否存在"一次实现、多游戏复用"的横向插件可能。
