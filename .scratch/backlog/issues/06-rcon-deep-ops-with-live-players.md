# 06 — RCON 深度操作用例（踢人/封禁/难度/换图验证，需真实玩家在线）

**Status:** ready-for-human

## 背景

清单 E2E-061~065 的 RCON 深度操作在本轮只覆盖了 `status` 执行与特殊字符命令（票 07）。踢人/封禁/难度切换的**效果断言**需要服务器上有真实玩家（或 bot）在线。

## 要补的用例

| ID | 操作 | 效果断言 |
|----|------|----------|
| E2E-062 | 踢人 | 玩家列表中出现目标 → 踢出 → 玩家消失（经 A2S/status 双确认） |
| E2E-063 | 封禁 | 封禁对话框 → addban 生效（ban 列表/日志可见） |
| E2E-064 | 切换难度 | mp_coop Mission 难度 CVAR 变更（经 status/cvar 查询确认） |
| E2E-092 | 防注入拦截 | **注意：当前 RCON 控制台无前端/后端拦截实现**（平台仅有 SourceMod CVAR 黑名单）——先做产品决策：是否要在 RCON 命令链路加拦截，再定用例 |

## 前置条件（环境）

- 一个能进真实玩家（或 SourceBot/模拟连接脚本）的 l4d2 服务器；
- RCON 密码可达。

## 实施要点

- 放在 `frontend/e2e/plugins/l4d2/` 用例包内，与票 07 共享 fixture；
- 玩家模拟：SourceMod 的 `sb_add` 可生成 SurvivorBot；bot 会出现在 `users` 与 `status`，但不计入 `status` 的 humans 数。

## 验收

- [x] 环境可行性结论：`laoyutang/l4d2-pure` 可直接使用 `L4D2_RCON_PASSWORD`，当前实例 RCON 可用；不需要等待官方 cm2network 镜像。
- [x] 用例实现并显式门控：`E2E_L4D2_LIVE_INSTANCE_ID` 未设置时整文件 SKIP，避免默认受管 E2E 误写真实服务器。
- [x] 难度切换：`sm_cvar z_difficulty` 后执行 `z_difficulty` 回读；命令未生效时返回业务失败。
- [x] 踢人：创建 bot、`users`/`status` 双确认、调用 kick API 后确认 `users` 中消失。
- [x] 封禁能力探测：`help banid`/`cvarlist banid` 与 `writeid` 可用，不对真实 SteamID 落盘。
- [ ] 封禁 UI 与 addban 生效断言：当前 RCON 页面没有封禁对话框，需产品实现后才能转为完整 E2E-063。

## 环境验证

2026-10-20（本 worktree、实例 97）：

- `status` 初始为 `0 humans, 0 bots (8 max)`；RCON `users` 可用。
- `sb_add` 生成 `Coach`/`Ellis` 等 SurvivorBot，`users` 可见，`status` 出现 `"BotName" BOT`；humans 计数仍为 0。
- `kick Coach` 与 `kickid <userid>` 都能让 bot 从 `users` 消失。
- `help banid`、`cvarlist banid`、`writeid` 可执行；`listplayers`/`banlist` 在该服务器不提供。
- 旧实现裸执行 `z_difficulty normal` 时 API 返回成功但值保持 `hard`；`sm_cvar z_difficulty normal` 生效，`z_difficulty` 回读为 `Normal`。因此票内“retag 镜像无法注入 RCON 密码”的说明已过时。

## 已实施

- `L4D2RconService.changeDifficulty()` 优先使用 `sm_cvar z_difficulty`，在 SourceMod 不可用时回退原生 setter，并在设置后回读校验；未生效时抛业务异常，不再假成功。
- 新增 `L4D2RconServiceDifficultyTest` 覆盖 SourceMod setter、无 SourceMod 回退、值未变化时拒绝成功。
- 新增 `frontend/e2e/plugins/l4d2/rcon-live-ops.spec.js`，覆盖难度、特殊字符、bot fixture、踢人、封禁能力探测与当前 UI 真实边界。
- 用例只在显式设置 `E2E_L4D2_LIVE_INSTANCE_ID` 时运行；写操作前拒绝人类玩家在线，结束后恢复原难度并清理自建 bot。

验证命令与结果：

```powershell
mvn -pl plugin-l4d2/plugin-l4d2-core -am `
  '-Dtest=L4D2RconServiceDifficultyTest,L4D2RconServiceChangeMapTest,RconControllerTest,StatusParserTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：29 tests，0 failures，0 errors，BUILD SUCCESS。

```powershell
$env:E2E_L4D2_LIVE_INSTANCE_ID = '97'
npx playwright test e2e/plugins/l4d2/rcon-live-ops.spec.js --workers=1
```

结果：6 passed（前端自动发现为 `http://127.0.0.1:3002`；3000 当时是无关的 MoviePilot 服务）。

## 阻塞与下一步

剩余阻塞是产品缺口，不是环境缺口：RCON 页面目前只有原始命令控制台，**没有踢人/封禁对话框**，因此 E2E-063 的“封禁对话框 → addban 生效”无法按清单原样实现；本票只验证了封禁命令能力探测，没有对真实 SteamID 执行破坏性封禁。

下一步由人工决定并排期：若产品需要封禁 UI，先实现对话框、玩家/SteamID 选择与结果反馈，再把本 spec 的探测用例转为完整封禁 E2E；若继续只保留原始控制台，应修改清单 E2E-062/063/064 的交互描述以反映现状。

RCON 任意命令的防注入拦截没有实现，且与“深度操作效果测试”是不同的产品决策，已拆到
[08-rcon-command-interception-decision.md](08-rcon-command-interception-decision.md)。
