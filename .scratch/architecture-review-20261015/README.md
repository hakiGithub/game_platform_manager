# 架构评审 2026-10-15 — 待办票（候选 1-8）

来源：`/improve-codebase-architecture` 于 2026-10-15 对 `game_platform_manger` 的架构评审
（快照 `b2f6b51`）。评审产出 9 个候选，其中**候选 9（前端 usePolling）已当轮落地**（见 ADR-0030），
本目录收录其余 8 个，供后续领取。

报告：`<TEMP>/architecture-review-20261015-010902.html`（自包含 HTML，未入库；如需归档请另存）。

## 票一览

| 票 | 一句话 | 强度 | 备注 |
|----|--------|------|------|
| [01](issues/01-patchinstall-payload-drops-fields.md) | PatchInstall 的 `headers`/`includePattern` 跨 seam 静默丢失 | **Strong** | **当前正在静默出错**，优先 |
| [02](issues/02-instance-state-reconciliation.md) | 实例状态对账三条路径，判定规则各说各话 | **Strong** | ADR-0005 只统一了词汇，未统一写入方 |
| [03](issues/03-remotehost-deep-module.md) | 45 处 SSH 调用绕过 DeploymentAccess seam | **Strong** | 工作量最大、杠杆最宽 |
| [04](issues/04-typed-task-payload.md) | 任务 payload 是 stringly-typed Map | Worth exploring | 票 01 是其前置 |
| [05](issues/05-host-tooling-port-neutrality.md) | VPK 语义住进 core，工具容器端口不再中立 | Worth exploring | **与 ADR-0027 冲突** |
| [06](issues/06-child-task-follower.md) | 子任务跟随与进度窗口重复两份 | Worth exploring | |
| [07](issues/07-host-session-module.md) | HostServiceImpl 四处重复「连主机→写在线状态」 | Worth exploring | 与票 03 合流 |
| [08](issues/08-l4d2-status-vocabulary.md) | 难度/模式词汇表 3 语言 4 副本、已漂移 | Worth exploring | |

## 一条贯穿的根因

票 01 / 04 / 08 是**同一个根因的三副面孔**：契约住在约定里（Map 键名、字符串字面量、重复表格），
而不是住在一个拥有的 module 里。票 02 / 03 / 07 是另一个根因：**同一个规则被多处实现，没有 owner**。

票 05 相反——一个**刻意中立的端口**（ADR-0026）被某款游戏的语义从旁边漏了回去。

## 已落地的候选 9

前端轮询收口：`usePolling` 双副本（主应用 `.js` + 插件 `.ts`）+ 4 个试点站点迁移 +
两侧 16 条单测 + ADR-0030。**其余 13 处轮询站点**待后续动到时按同一模式迁移（该票不含在册，
因决策与模式已固化在 ADR-0030，迁移属机械工作）。

## 上一轮评审（2026-08-13）的落地情况

候选 1-4 已落地于 `5b942f9`：`DeploymentAccess`、`FileService` 归并、`ConnectionLifecycle`/`LogTailer`、
`InstanceStatus` 词汇表。**本轮不重提这四项**——但票 03 指出 `DeploymentAccess` 的 seam
只覆盖了凭据解析、没包住传输层，故 45 处又从旁边绕回。
