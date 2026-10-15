# ADR-0030: 前端 usePolling 双副本（行为类工具同样走复制）

- 状态：Accepted
- 日期：2026-10-15
- 关联：[ADR-0007](0007-plugin-frontend-nightops-token-isolation.md)（前置决策：token 与小体量工具复制而非共享）、[ADR-0005](0005-run-status-vocabulary-unification.md)（后端枚举作为唯一文本源的先例）

## Context

平台的两个前端（主应用 `frontend/`、插件子应用 `backend/plugin-l4d2/frontend/`）各自手写轮询生命周期。
2026-10-15 的架构评审（候选 9）盘点出 **17 个文件、20 个 `setInterval` 定时器**，每个站点都重复同一套三段样板
（声明 `let xTimer = null` → 挂载时起表 → 卸载时清表），且已出现可见分歧：

| 分歧项 | 现状 |
|---|---|
| 门控语义 | 无条件轮询 / 条件为假时**空转**（定时器照跑、只跳过拉取）/ 条件为假时**自停** |
| 自停后恢复 | `instance/index` 仅在 `onMounted` 武装一次，自停后再无恢复路径（缺陷） |
| 清理 hook | `onUnmounted` 与 `onBeforeUnmount` 混用 |
| 单次失败 | 部分站点吞掉抖动、部分站点依赖 fetcher 内部 try/catch |
| 请求堆叠 | `setInterval` 无在途保护，慢接口下（如 `Monitor.vue` 1s 间隔）请求会叠加 |

ADR-0007 已确立「插件前端复制主应用产物而非跨目录共享」这一原则，但其授权范围被明确限定为
**「小体量、声明式」工具**（先例：23 行的 `terminalTheme.js` 常量表、`status-dot` 样式）。
`usePolling` 不落在该范围内——它是一个**带逻辑、会演化**的行为模块。因此需要一条新决策回答两件事：
这个模块该长成什么样，以及它在两个前端之间如何共存。

## Decision

### D1 抽取为轮询生命周期深模块，承载三条不变量

`usePolling(fetcher, { intervalMs, enabled, immediate, onError })` → `{ refresh, isPolling }`。
调用方无须知道机制，只需知道模块保证的三条不变量：

1. **至多一个在途请求** —— 递归 `setTimeout` + in-flight 守卫。慢接口下请求速率自动降为「实际耗时」，
   绝不堆叠。这是对旧 `setInterval` 写法的实质修正，而非把定时器挪个位置。
2. **固定节拍** —— 每次拉取完成后才排下一次，间隔 = `intervalMs`。
3. **`enabled` 是唯一真值源** —— 定时器只在 `enabled()` 为真时存在；翻真即起表、翻假即停表。

机制上由内部 `watch(enabled)` 驱动起停，**三种门控语义（无条件 / 空转 / 自停）收敛为同一个参数**：
无条件即省略 `enabled`，空转与自停的差别消失（条件为假时一律停表——空转是纯粹的浪费）。

**不暴露 `start`/`stop`**：那会与 `enabled` 形成两个真值源，调用方可以进入「`enabled` 为假但表在跑」的矛盾态。
唯一的命令式出口是 `refresh()`（立即拉一次、不重排节拍）。

**起表 ≠ 拉取**：`enabled` 翻真只起表，首次数据等下一个 tick。需要立即数据的调用方显式 `refresh()`。

### D2 行为变更随决策一并接受

抽取过程中同步修正四处已识别的分歧，属于**有意的行为变更**而非无感重构：

- 自停站点的门控翻真即恢复轮询（修 `instance/index`「自停后永不恢复」缺陷）。
- 空转站点改为条件为假时停表（`host/detail` 离线主机不再每 10s 空跑）。
- 清理统一为 `onBeforeUnmount`。
- 单次失败默认吞掉并 `console.warn`，需要感知的调用方接 `onError`。

### D3 不取消在途请求

模块不持 `AbortController`，也不向 fetcher 透传 `AbortSignal`。理由：本轮要消灭的真实缺陷是**请求堆叠**，
已由 in-flight 守卫解决；abort 需改动 `request.js` 与插件 `api/index.ts` 的 `get<T>()` 两层且两侧仓库零先例。
卸载后未取消的请求其 `.then` 仍会执行，但在 Vue 3 下只写入脱离组件树的 ref，无副作用。

### D4 两副本共存：复制 + 靠人移植，接受扩展名差异

插件侧**复制**一份实现（`backend/plugin-l4d2/frontend/src/composables/usePolling.ts`），不跨构建引用主应用源码——
这是 ADR-0007「复制而非共享」原则在**行为类工具**上的延伸。与 ADR-0007 的样式/token 副本相比，本条有两处差异必须显式记录：

1. **副本间不是逐字复制**：主应用是 `.js`（视图层无 TS），插件侧是 `.ts`（带接口类型）。
   同步是**移植逻辑**，不是整文件覆盖。
2. **用例集应对齐**：两侧单测（主应用 `frontend/src/tests/composables/usePolling.test.js`、
   插件 `.../composables/usePolling.test.ts`）刻意保持同一组用例，作为「两副本行为等价」的可执行约定。

## 后果（Consequences）

- **正面**：17 处轮询的半样板消失；门控/清理/失败处置各只有一处真值；
  轮询生命周期首次可被单测直接验证（假定时器），无需挂载视图。
- **负面**：主应用侧改动需**手动移植**到插件副本，且因扩展名不同无法整文件复制——
  这是一条靠人记得同步的路，漂移风险高于 ADR-0007 的样式副本。
  同步点：`frontend/src/composables/usePolling.js` ↔ `backend/plugin-l4d2/frontend/src/composables/usePolling.ts`，
  两侧用例 `frontend/src/tests/composables/usePolling.test.js` ↔ `.../composables/usePolling.test.ts` 同理。
- **前置假设**：模块假定「组件卸载即离开页面」。当前路由层无 `<keep-alive>`（已核实）；
  若将来引入，需补 `onDeactivated` 停 / `onActivated` 按 `enabled` 恢复。此假设已写在两副本的文件头注释中。
- **范围**：本轮仅迁移 4 个代表站点（主应用 3 + 插件 1，覆盖无条件/空转/自停三种门控与 `onMounted`/响应式两种触发源），
  其余 13 处待后续动到时按同一模式迁移。
- **不在本决策范围**：`DeployProgress.vue` 的耗时计时器（界面计时，非轮询）、`useLogStream.ts` 的重连节拍
  （由错误触发而非固定间隔，属另一模块）、`AbortSignal` 透传（D3）。
- 覆盖率：`frontend/vitest.config.js` 的 coverage `include` 已纳入 `src/composables/**/*.js`，
  否则新模块不进 70% 行覆盖门禁。

## 备选方案

1. **跨构建共享（单一源码，两侧引入）** —— 被否：与 ADR-0007 的隔离原则直接冲突，
   且插件子应用独立构建、独立发布，共享源码会引入构建耦合。
2. **插件侧内联一份更简实现，不做副本同步** —— 被否：两处实现会立即开始漂移，
   而轮询语义（在途守卫、门控）恰恰是必须全网一致的那类逻辑。
3. **软链或构建期拷贝** —— 被否：引入新的构建机制（且软链在 Windows 上有已知麻烦），
   而 ADR-0007 已确立「复制 + 头部注明同步关系」的现成模式，扩展它比新建机制便宜。
4. **保守版：三档门控显式枚举、行为零变更** —— 被否：接口更大（三个参数表达一件三态的事），
   且放弃了两个真实缺陷的修复机会（`instance/index` 永不恢复、空转站点空发请求）。
