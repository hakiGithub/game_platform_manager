# 上游候选仓库核验清单

核验时间：2026-10-21。所有条目经 GitHub REST API（`/repos/{owner}/{repo}`）或
`raw.githubusercontent.com` 实际访问确认。`MISS` 表示已尝试但仓库不可访问，**不进入结论**。

## A. 通用游戏服管理面板

| 仓库 | stars | 语言 | 核验 | 能力概括 | 平台相关性 |
|------|-------|------|------|----------|-----------|
| pterodactyl/panel | 9358 | PHP | OK | 通用面板：Docker 隔离、Egg 配置模板、子用户、API | 功能对照基准 |
| pelican/panel | 2394 | PHP | OK | Pterodactyl 的 Laravel 后继者 | 同上 |
| pterodactyl/wings | 1062 | Go | OK | 守护进程：容器生命周期、安装脚本、备份、文件、WS 控制台 | **宿主能力对照核心** |
| pelican-eggs/eggs | 3091 | - | OK（更新至 2026-10-18） | 数百游戏的 Egg（启动参数 + 变量 + 安装脚本） | **配置 schema 来源** |
| MCSManager/MCSManager | 4995 | - | OK | 分布式实例管理、文件、终端、计划任务 | 实例/文件能力对照 |
| pufferpanel/pufferpanel | 1754 | Go | OK | 轻量管理面板 | 轻量取舍参考 |
| easy-wi/developer | 246 | PHP | OK | 老牌游戏/语音服 Webinterface | 历史参考 |
| brainfoolong/rcon-web-admin | 80 | - | OK | 纯 RCON Web 控制台，多游戏语义 | RCON 语义层参考 |
| gamedig/node-gamedig | 737 | JS | OK | 游戏服查询协议库（跨引擎） | **查询（玩家数）能力上游** |

## B. 部署/运维底座

| 仓库 | stars | 核验 | 能力概括 |
|------|-------|------|----------|
| GameServerManagers/LinuxGSM | 4912 | OK | 平台 `linuxgsm` / `linuxgsm-docker` 部署方式的底座；**34 个 `_default.cfg` 已抓取** |

`lgsm/modules/` 实际命令面（API 核验的目录清单，非推测）：

- **生命周期**：`start` `stop` `restart` `monitor` `details` `postdetails` `send` `console`
- **数据**：`backup` `validate` `wipe` `update` `check_update` `update_linuxgsm`
- **模组**：`mods_install` `mods_remove` `mods_update` `mods_list` `workshop_update` `workshop_core`
- **诊断**：`debug` `test_alert` `check_ip` `check_status` `check_config` `check_deps` `check_logs` `check_version`
- **告警**：`alert` + discord/email/gotify/ifttt/matrix/ntfy/pushbullet/pushover/rocketchat/slack/telegram 共 11 种
- **修复**：`fix` + 40 个游戏专属 `fix_*`（ark/rust/vh/sdtd/squad/st/sm/...）

## C. Source 引擎生态

| 仓库 | stars | 核验 | 能力概括 |
|------|-------|------|----------|
| alliedmodders/sourcemod | 1150 | OK | 服务端 mod 框架 + admin 体系 |
| sbpp/sourcebans-pp | 375 | OK | Source 系全局 ban / admin / 禁言 Web 管理（Elastic 2.0，web/ 与插件分离授权） |
| counterstrikesharp-panel/css-bans | 145 | OK | CSSharp 封禁面板（CS2 世代的 SourceBans 替代形态） |

## D. Minecraft 家族

| 仓库 | stars | 核验 | 能力概括 |
|------|-------|------|----------|
| itzg/docker-minecraft-server | 14458 | OK | MC Java 版容器化：版本/模组加载器/modpack 自动安装 |
| itzg/docker-minecraft-bedrock-server | 1874 | OK | Bedrock 版容器化 |
| overviewer/Minecraft-Overviewer | 3505 | OK | 世界地图渲染 |

## E. 生存/沙盒

| 仓库 | stars | 核验 | 能力概括 |
|------|-------|------|----------|
| thijsvanloef/palworld-server-docker | 3219 | OK | 帕鲁服务端容器（平台默认镜像）。README 实测：`RCON_ENABLED`(默认 false)、`RCON_PORT` 25575、`REST_API_ENABLED`(默认 true)、`REST_API_PORT` **8212**、`AUTO_PAUSE_ENABLED`、`ENABLE_PLAYER_LOGGING` |
| jammsen/docker-palworld-dedicated-server | 1009 | OK | 同类镜像。README 明确：**已移除 RCON**，全部改用官方 REST API；`restapicli` 子命令 `players`/`info`/`metrics`/`settings`/`gamedata`/`save` |
| cheahjs/palworld-save-tools | 866 | OK | 帕鲁存档解析（Python，转 JSON） |
| community-valheim-tools/valheim-server-docker | 2393 | OK | Valheim 容器（活跃：2026-10-20） |
| OxideMod/Oxide.Rust | 387 | OK | Rust 服务端 mod 框架 |
| fpsacha/zomboid-control-panel | 128 | OK | PZ 专用控制面板（单游戏面板的形态样本） |

## F. 核验失败的（记录以免重复尝试）

`Crafty-Controller/Crafty-Controller`、`Crafty-Controller/Crafty`、`Crafty-Controller/Crafty-4`、
`arkmanager/ark-manager`、`ArkServerManager/ASM`、`Nimdy/Ark-Server-Tools`、`Valheim-Modding/V-Plus`、
`itzg/mc-server-wrapper` —— 全部 MISS。Crafty Controller 的官方仓库位置需要人工确认，暂不作为依据。

## G. 配额说明

匿名 GitHub API 限额为 core 60 次/小时、search 10 次/小时。本轮调研已用尽大部分配额，
故改用 `raw.githubusercontent.com` 直接抓取源文件（不消耗 API 配额）作为主要取证手段。
若需继续补全，建议配置 `GITHUB_TOKEN` 或改用 `gh` CLI 认证后重跑。
