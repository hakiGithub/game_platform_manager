# 02 — 实例状态对账有三条独立路径，判定规则各说各话

**Status:** resolved
**Type:** task
**来源:** 架构评审 2026-10-15（候选 3）

## 现象

「实例现在是什么状态」这一个概念，被**四条触发路径、三套判定算法**各自回答：

| 路径 | 位置 | 判定依据 |
|---|---|---|
| A 定时 | `DockerInstanceSyncStrategy` :146-190 | `docker ps` → 容器匹配 |
| B 定时 | `NativeInstanceSyncStrategy` :52-120 | `pgrep -f "-game <key>"` |
| C 按需 | `InstanceServiceImpl.refreshStatus` :565-600 | `adapter.getStatus()`（部署适配器） |
| D 启动 | `InstanceSyncStartupRunner` | 复用 A/B |

调其中一处不改变另两处的结论。

## 根因

ADR-0005 已把 `InstanceStatus` 定为唯一**词汇表**，但「谁写 `run_status` 这一列」从未收口——
词汇统一了，写入方仍有三处。

附带缺陷：路径 C 的 catch 分支把**基础设施故障**写成实例进入 `ERROR` 状态
（`InstanceServiceImpl:598`），即「查不到 ≠ 出错了」被混为一谈。

## 修复建议

抽 `InstanceStateReconciler` 深模块，把**观测**与**判定**分离：

```
observe(instanceId) → ObservedState        // 容器 / 进程 / 适配器，三种观测源
reconcile(observed, dbStatus) → Decision   // 纯函数：观测 ↔ 库中状态 → 决策
```

定时 / 按需 / 启动只是不同的**触发方式**，共用同一判定。判定为纯函数，
可直接单测，无需 SSH 或真实主机。

## 验收

- 判定逻辑有纯函数单测（不依赖 SSH）。
- 四条路径共用同一判定；新增部署类型只加观测、不加路径。
- 基础设施故障不再把实例写成 `ERROR`（与真实「异常」状态区分）。

## 关联

与候选 7（HostSession）天然合流——都涉及「连主机 + 对账」这一组行为的收口。

## Answer

- 新增纯函数 `InstanceStateReconciler`，统一根据“库中状态 + 已成功观测状态”产出写入决策；Docker 定时同步、Native 定时同步、启动同步与按需 `adapter.getStatus()` 共用该判定。
- 适配器、Docker inspect、SSH/pgrep 查询失败均保留最后一次已知状态，不再把基础设施故障写成 `ERROR`；仅适配器明确返回 `ERROR` 时更新为异常。
- 补充纯函数测试及 Docker/Native/按需刷新回归用例，覆盖状态转换、无变化、真实异常和观测失败。
- 提交：`9538789`（合并提交 `c9060f4`）。
