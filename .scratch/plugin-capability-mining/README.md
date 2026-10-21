# plugin-capability-mining

从 GitHub 上游生态挖掘"平台已支持游戏"的 Web 管理 / GM 工具能力，整理成能力目录与插件路线图。

- **给人读的分析报告：[REPORT.md](REPORT.md)** ← 从这里开始
- Spec: [spec.md](spec.md)
- 调研产物目录：`research/`（由票 01-05 写入）
- 决策产物：`capability-catalog.md`、`roadmap.md`（由票 06 写入）

## 票状态

| 票 | 类型 | 状态 | 阻塞 |
|----|------|------|------|
| [01 上游候选仓库核验清单](issues/01-upstream-repo-inventory.md) | research | resolved | - |
| [02 Source 引擎生态调研](issues/02-research-source-engine.md) | research | ready-for-agent | 01 |
| [03 Minecraft 家族调研](issues/03-research-minecraft-family.md) | research | ready-for-agent | 01 |
| [04 生存/沙盒类调研](issues/04-research-survival-family.md) | research | ready-for-agent | 01 |
| [05 通用面板宿主能力面调研](issues/05-research-generic-panel-capabilities.md) | research | ready-for-agent | - |
| [06 汇总：能力目录 + 路线图](issues/06-synthesize-catalog-and-roadmap.md) | task | resolved | 01-05 |

## 执行建议

01 和 05 无前置，可同时开跑；02/03/04 等 01 出清单后并行；06 最后收口。
05 独立于游戏族，可与 02/03/04 同时推进，只有 06 需要等全部调研完成。

## 当前进度（2026-10-21）

已完成 01 与 06。主产物是 **[REPORT.md](REPORT.md)**（给人读的分析报告，v2 已修订）。

- [research/00-repo-inventory.md](research/00-repo-inventory.md) — 22 个已核验仓库 + 核验失败归档
- [capability-catalog.md](capability-catalog.md) — 现状基线、七域宿主能力、引擎族能力面、单游戏重点能力
- [roadmap.md](roadmap.md) — 三个候选插件与执行顺序

### v2 修订（重要）

初版只在 `game_platform_manger/backend/` 下搜索，**漏了同级目录 `D:\program\ai\` 下的
三个独立插件仓库**：`plugin-7dtd`（sdtd）、`plugin-dnf-tw`、`plugin-dst`。
它们都已打包进 `backend/plugins/`。实际插件数是 **4 个**，不是 1 个。

修正后的关键结论：

1. 插件是"游戏的管理台本体"，不是附加功能（`sdtd`/`dst`/`dnf_tw` 的 yml 元数据全是 0，
   但各有 6/7/10 个管理菜单）
2. 四个插件走**四条不同的通信路线**：RCON 直连（l4d2）、tmux send-keys（dst，DST 无 RCON）、
   直连 MySQL（dnf-tw）、HTTP Web API（7dtd）
3. 存在**重复造轮子**：两套配置解析、两套备份、两套玩家统计、四套日志解析
4. 路线图排序改为 **P0 抽公共层 → P1 告警 → P2 plugin-source → P3 plugin-palworld**

**教训**：没有任何一处记录"哪些游戏有插件、插件仓库在哪"，只能靠猜。
建议补一份插件登记表。

**取证方法说明**：匿名 GitHub API 限额 core 60/小时、search 10/小时且已耗尽。
改用 `raw.githubusercontent.com` 直接抓源文件（不消耗配额），拿到的是
LinuxGSM 的 `lgsm/modules/` 全量目录与 34 个游戏的 `_default.cfg` —— 属于一手源码实证。

02/03/04 三份调研票仍为 `ready-for-agent`。它们要回答的是**实现前的细节**（SourceMod 权限模型、
modpack 契约、存档工具运行时依赖），对"审批优先级"这个目标不构成阻塞，故 06 先行收口。
进入实现前建议补齐，或配置 `GITHUB_TOKEN` 重跑。

## 数据产物

| 文件 | 内容 | 生成脚本 |
|------|------|----------|
| `research/lgsm-capabilities.csv` | 34 个游戏的 LinuxGSM 权威字段 | `research/parse-lgsm.ps1` |
| `research/platform-games.csv` | 139 个平台游戏的已声明能力 | `research/parse-platform-games.ps1` |
| `research/game-capability-matrix.csv` | 34 游戏能力矩阵 | `research/parse-matrix.ps1` |
| `research/full-matrix.csv` | 139 游戏全量矩阵 | `research/build-full-matrix.ps1` |
| 外部插件清单 | 4 个插件的菜单/Controller/规模/通信路线 | `research/scan-external-plugins.ps1` |
| `research/_raw/` | 抓取的原始配置与 README | 抓取脚本内联执行 |
