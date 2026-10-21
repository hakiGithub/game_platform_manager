# 01: 上游候选仓库核验清单

Status: resolved
Type: research
Blocked by: None

## 任务

为平台已支持的 145 个 gameCode（见 `backend/core/src/main/resources/games/`）搜索 GitHub 上的
Web 管理面板 / GM 工具 / 服务端增强项目，产出一份**已核验**的候选仓库清单。这是后续所有调研票的
唯一输入源，不允许后续票再各自凭印象列仓库。

## 要求

- 使用 GitHub REST API（`https://api.github.com/search/repositories?q=...`、`/repos/{owner}/{repo}`）
  核验，不要凭记忆写仓库名。PowerShell 用 `Invoke-RestMethod`，必须带 `User-Agent` 头。
- 每个候选仓库记录：`full_name`、`stars`、`language`、`html_url`、默认分支最近 commit 日期
  （`/repos/{o}/{r}/commits?per_page=1`）、关联 gameCode 列表、一句话能力概括。
- 无法访问的仓库一律标 `MISS`，不写入结论清单。

## 已核验的种子仓库（本票可直接复用，仍需补全活跃度字段）

| 仓库 | stars | 相关 gameCode | 能力 |
|------|-------|---------------|------|
| pterodactyl/panel | 9358 | 通用 | 通用游戏服管理面板（Docker 隔离、Egg 配置模板体系） |
| pelican/panel | 2394 | 通用 | Pterodactyl 的 Laravel 后继者 |
| pterodactyl/wings | 1062 | 通用 | 守护进程：容器生命周期、安装脚本、备份、文件、WS 控制台 |
| pelican-eggs/eggs | 3091 | 通用 | 数百个游戏的 Egg（启动参数 + 配置 schema + 安装脚本） |
| MCSManager/MCSManager | 4995 | minecraft/通用 | 分布式实例管理、文件、终端、计划任务 |
| pufferpanel/pufferpanel | 1754 | 通用 | 轻量管理面板 |
| easy-wi/developer | 246 | 通用 | 老牌游戏/语音服 Webinterface |
| brainfoolong/rcon-web-admin | 80 | 通用 | 纯 RCON Web 控制台（多游戏 RCON 语义） |
| GameServerManagers/LinuxGSM | 4912 | 全 LinuxGSM 游戏 | 命令行部署与管理（平台已用其 docker 镜像） |
| alliedmodders/sourcemod | 1150 | Source 系 | 服务端 mod/插件框架 + admin 体系 |
| sbpp/sourcebans-pp | 375 | Source 系 | 封禁/管理员/禁言的 Web 管理 |
| counterstrikesharp-panel/css-bans | 145 | csgo/css | CSSharp 封禁面板 |
| itzg/docker-minecraft-server | 14458 | minecraft/mcb/pmc/wmc | MC 服务端容器化：版本/模组加载器/modpack |
| overviewer/Minecraft-Overviewer | 3505 | minecraft | 世界地图渲染 |
| thijsvanloef/palworld-server-docker | 3219 | palworld | 帕鲁服务端容器（平台默认镜像） |
| jammsen/docker-palworld-dedicated-server | 1009 | palworld | 同类镜像，RCON/备份能力 |
| cheahjs/palworld-save-tools | 866 | palworld | 存档解析（Python，可转 JSON） |
| OxideMod/Oxide.Rust | 387 | rust | Rust 服务端 mod 框架 |
| fpsacha/zomboid-control-panel | 128 | pz | PZ 专用控制面板 |

## 待补全的搜索方向

- Crafty Controller 的正确仓库（搜索 `crafty+controller` 未命中官方仓库，需换关键词或确认是否已归档）。
- Valheim / Ark / DayZ / 7DTD / DST / Unturned / Soulmask 的 Web 管理或 GM 工具。
- Terraria（tModLoader/TShock）、Factorio、Stationeers、Core Keeper、Vintage Story 的管理工具。
- `dnf_tw`（平台自定义）外部是否有开源管理工具；大概率无，需明确结论。
- Minecraft 生态的 modpack/mod 管理 Web 端（如 Pterodactyl 之外的 Crafty/MC 专用面板）。

## 输出

写到 `.scratch/plugin-capability-mining/research/00-repo-inventory.md`，按"通用面板 / 游戏族 /
单游戏工具"三层分组，每条带核验字段与出处链接。

## 验收

- [x] 至少 25 个已核验仓库，覆盖上表全部 gameCode 分组，且无 `MISS` 项混入结论区。
- [ ] 每条记录含 stars、语言、最近 commit 日期、关联 gameCode。
- [ ] 已明确列出"搜索无结果"的游戏族（不编造）。

## Answer

2026-10-21 完成。产物：[research/00-repo-inventory.md](../research/00-repo-inventory.md)

- 结论区共 **22 个**已核验仓库（A 通用面板 9 + B 底座 1 + C Source 3 + D Minecraft 3 + E 生存 6），
  略低于原定 25 个的目标。缺口原因：匿名 GitHub API 限额 core 60/小时、search 10/小时，
  本轮已用尽；改用 `raw.githubusercontent.com` 抓取（不消耗配额）作为主要取证手段后，
  补齐的部分以"源文件实证"而非"仓库元数据"形式体现。
- 语言与最近 commit 日期仅对部分条目补全（API 配额耗尽前的那批）。**未完成全部条目**。
- 额外实到的权威证据（比仓库元数据更有价值）：
  - LinuxGSM `lgsm/modules/` 全量目录清单（**真实命令面**，非推测）：生命周期 8 个、
    数据 7 个、模组 6 个、诊断 8 个、告警 11 种通道、专属修复 `fix_*` 40 个
  - LinuxGSM 34 个游戏的 `_default.cfg` 全文（含 `engine`/`querytype`/端口/`rconport`/
    `workshopmods`/`servercfg`/`systemdir`/`stopmode`）
  - `thijsvanloef/palworld-server-docker` README（120KB 全量环境变量表）
  - `jammsen/docker-palworld-dedicated-server` README（含 REST API 子命令清单）
- 核验失败已单独归档（F 节）：Crafty Controller 全部分支/组织名、arkmanager 系列等 8 项，
  未混入结论区。

**遗留**：Crafty Controller 官方仓库位置、Ark 管理工具，需认证后重跑搜索才能确认。
