# 插件登记表

> 本表是**权威的"游戏 ↔ 插件"映射**。新增或移动插件仓库时，必须同步更新本表。
>
> 存在理由：2026-10-21 的能力盘点调研曾因缺少本表而漏掉三个插件 ——
> 插件仓库不在主工作区内，而 `games/{gameCode}.yml` 里没有任何字段能看出该游戏有没有插件
> （`sdtd` / `dst` / `dnf_tw` 的 `configSchema` 与 `customOperations` 全为 0，但各有完整插件）。

## 为什么需要这张表

平台的游戏能力分两层，但两层之间没有显式关联：

| 层 | 位置 | 职责 |
|----|------|------|
| 游戏元数据 | `backend/core/src/main/resources/games/{code}.yml` | 只负责**把服务器装起来**（部署模板、端口、镜像、依赖） |
| 插件 | 独立仓库，见下表 | **这个游戏的 Web 管理台**（全部操作界面与语义化能力） |

因此 `games/*.yml` 里 `configSchema: 无`、`customOperations: 0`
**不代表这个游戏没有插件**。判断有无插件只能查本表。

---

## 已上线插件（4 个）

| plugin.id | gameCode | 游戏 | 仓库位置 | 仓库类型 | 通信路线 |
|-----------|----------|------|---------|---------|---------|
| `plugin-l4d2` | `l4d2` | 求生之路2 | `backend/plugin-l4d2`（主仓库内） | 主仓库子模块 | RCON 直连 |
| `plugin-dst` | `dst` | 饥荒联机版 | `D:\program\ai\plugin-dst` | 独立 git 仓库 | tmux send-keys + 日志解析 |
| `plugin-dnf-tw` | `dnf_tw` | DNF 台服 | `D:\program\ai\plugin-dnf-tw` | 独立 git 仓库 | 直连游戏 MySQL |
| `plugin-sdtd` | `sdtd` | 7 Days to Die | `D:\program\ai\plugin-7dtd` | 独立 git 仓库 | 游戏自带 HTTP Web API |

> **注意命名不一致**：`plugin-sdtd` 的仓库目录名是 `plugin-7dtd`（游戏俗称），
> 而 `plugin.id` 与 gameCode 用的是 LinuxGSM 短名 `sdtd`。搜索时两个名字都要试。

### 规模与能力面

| plugin.id | Java 文件 | 后端行数 | 前端页面 | Controller | 菜单 | 部署 jar 位置 |
|-----------|----------|---------|---------|-----------|------|--------------|
| `plugin-l4d2` | 223 | 25681 | 29 | 20 | 18 | `backend/plugins/` |
| `plugin-dst` | 56 | 6437 | 11 | 8 | 7 | `backend/plugins/` |
| `plugin-dnf-tw` | 47 | 4939 | 12 | 12 | 10 | `backend/plugins/` |
| `plugin-sdtd` | 25 | 1861 | 8 | 7 | 6 | `backend/plugins/` |

### 各插件的管理菜单

**`plugin-l4d2`**（18 项，六组）
仪表盘 · 配置管理（服务器配置 / 服务器信息 / 预设场景）·
运维控制（控制台 / 系统监控 / 日志 / 重启管理）·
地图管理（地图管理 / 地图中心）·
玩家管理（玩家统计 / 管理员）·
扩展与数据（插件管理 / 备份还原 / 下载管理）

**`plugin-dst`**（7 项）
服务器控制台 · 房间设置 · 玩家管理 · 玩家统计 · Mod 管理 · 备份管理 · 定时任务

**`plugin-dnf-tw`**（10 项）
仪表盘 · 连接管理 · 账号管理 · 角色管理 · 邮件发放 · 在线监控 ·
活动管理 · 拍卖行 · 字典管理 · 日志查询

**`plugin-sdtd`**（6 项）
仪表盘 · 控制台 · 玩家管理 · 日志 · 事件记录 · Modlet 管理

---

## 通信路线说明（做新插件前必读）

四个插件走四条**完全不同**的路线。"照着 l4d2 抄 RCON"只对 Source 引擎系成立。

| 路线 | 适用 | 实现要点 | 参考插件 |
|------|------|---------|---------|
| **RCON 直连** | Source 引擎系 | 用宿主 `RconService`（ADR-0016），端点只认标准键 `rconPort`（缺省 27015）/ `rconPassword` | `plugin-l4d2` |
| **tmux send-keys + 日志解析** | 无 RCON 的游戏（如 DST） | `docker exec -u linuxgsm <容器> tmux -L <socket> send-keys -t <会话> "<cmd>" Enter`，再解析控制台日志取结果。注意容器内用户与 socket 动态后缀（见 `plugin-dst` ADR-0002） | `plugin-dst` |
| **游戏自带 HTTP/REST API** | 有 Web API 的游戏 | JDK `HttpClient` + 鉴权（7dtd 用 Basic；帕鲁 REST 默认开启，端口 8212） | `plugin-sdtd` |
| **直连游戏数据库** | 私服 / GM 工具形态 | MySQL JDBC 直连游戏库（dnf-tw 连 5 个库：d_taiwan / taiwan_cain / taiwan_cain_2nd / taiwan_billing / taiwan_login） | `plugin-dnf-tw` |

**未接的现成能力**（元数据已具备但没用起来）：

- `fctr`（Factorio）：LinuxGSM 已声明 `rconport=34198` + `rconpassword`，但 `fctr.yml` 未声明标准键 → 补几行 YAML 即可有 RCON
- `palworld`：REST API 默认开启（端口 8212）未接
- 创意工坊：LinuxGSM `workshopmods` 只开了 dayz / l4d2 / squad / st 四个，整个 Source 系都支持

---

## 有配置表单但无插件的游戏（4 个）

这些有 `configSchema` 但**没有插件管理台**，是"半完成"状态：

| gameCode | 游戏 | configSchema | customOperations |
|----------|------|-------------|-----------------|
| `rust` | Rust | 20 键 | 7（备份 / 更新 / 更新Oxide / 清理实体 / 清理垃圾 / 重启 / 执行RCON命令） |
| `palworld` | 幻兽帕鲁 | 15 键 | 4（备份存档 / 更新服务器 / 广播消息 / 保存世界） |
| `valheim` | 英灵神殿 | 11 键 | 5（备份世界 / 更新服务器 / 查看在线玩家 / 保存世界 / 重置世界） |
| `minecraft` | Minecraft | 9 键 | 4（备份存档 / 清理日志 / 查看在线玩家 / 重启服务器） |

---

## 覆盖统计

| 指标 | 数量 |
|------|------|
| 平台内置游戏 | 139 |
| 有插件 | 4 |
| 有配置表单 | 5 |
| 插件 ∪ 配置表单 | **8**（dnf_tw / dst / l4d2 / minecraft / palworld / rust / sdtd / valheim） |
| 仅有部署模板 | **131** |
| 部署方式分布 | 133 个仅 `linuxgsm-docker`，5 个多种，1 个 `docker-compose` |

---

## 维护约定

1. **新增插件**：在本表"已上线插件"加一行，填 `plugin.id` / `gameCode` / 仓库位置 / 通信路线。
   若仓库不在主工作区内，**必须写绝对路径**。
2. **移动或重命名仓库**：同步改本表的"仓库位置"列，并核对 `plugin.id` 是否也变了。
3. **删除插件**：从表移除，并在下方"历史插件"留一行说明。
4. **插件仓库内**应保留 `plugin.properties`（PF4J 必需），其中 `plugin.gameCode` 是本表的关联依据。

### 相关文档

- [ADR-0001 插件菜单归属](design/adr/0001-plugin-menu-ownership.md)
- [ADR-0002 主应用与插件范围隔离](design/adr/0002-main-app-plugin-scope-isolation.md)
- [ADR-0016 RCON 宿主能力](design/adr/0016-rcon-host-capability.md)
- 插件开发指南：`.trae/skills/gameplatform-plugin-dev/SKILL.md`（用户级副本 `~/.agents/skills/gameplatform-plugin-dev/`）
