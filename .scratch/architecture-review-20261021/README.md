# 架构评审 2026-10-21 — 待办票（候选 1-5）

来源：`/improve-codebase-architecture` 于 2026-10-21 对 `game_platform_manger` 的架构评审
（分支 `codex/integration-triage-20261020`，快照 `9cd53fc`）。

报告：`<TEMP>/architecture-review-20261021-020502.html`（自包含 HTML，未入库）。

扫描范围：按近 200 次提交的热点定位——L4D2 插件 service 集群与 core 的部署/适配器区域。

## 票一览

| 票 | 一句话 | 强度 |
|----|--------|------|
|[01](issues/01-collapse-compose-adapters.md) | 两个 Compose 适配器九项助手逐字重复，副本已漂移 | **Strong** |
|[02](issues/02-deploy-type-vocabulary-owner.md) | 部署方式词汇表无 owner，前端已两处拼写不一致 | **Strong** |
|[03](issues/03-task-status-vocabulary-deepening.md) | 任务状态被两个前端重述，插件侧有两个枚举外状态 | Worth exploring |
|[04](issues/04-typed-deploy-config-seam.md) | 部署配置是无类型 Map，同一份数据组装两遍规则不同 | Worth exploring |
|[05](issues/05-instance-file-route-adapters.md) | 实例文件访问靠 20 处 `isNative` 分支区分两种传输 | Speculative |

## 一条贯穿的根因

票 02 / 03 / 04 是**同一个根因的三副面孔**：契约住在约定里（字符串键、重复表格、
VO 只给原始 status），而不是住在一个拥有的 module 里。

票 01 / 05 相反：**seam 划对了，实现没有跟随 seam 分叉**——`DeployAdapter` 按部署方式
切分而实现按「是否用 compose」切分；`FileRoute` 分叉了而执行没有。

## 已收口的先例（本轮不重提）

`.scratch/architecture-review-20261015/` 的 8 个候选全部 resolved，
其中票 02（`InstanceStateReconciler`）、票 03（`RemoteHost`）、票 06（`ChildTaskFollower`）
已把「同一规则多处实现」这一类问题大幅清空。本轮剩下的 01 / 05 是该模式仅存的残留。

`usePolling`（ADR-0030）已落地；其余 13 处轮询站点属机械迁移，不在册。

## 建议顺序

1. **票 01** — 唯一「同一规则写两遍且副本已漂移」的候选，`mountHostCerts` 承重，
   改动机械、可由现有测试护栏验证。
2. **票 02** — 只需复制 `instanceStatus.js` 的既有模式，风险低。
3. 票 03 / 04 需先确认契约能否改（任务 VO 字段、适配器签名）。
4. 票 05 建议在下次动 `InstanceFileServiceImpl` 做功能时顺势拆分。

