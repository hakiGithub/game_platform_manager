# ADR-0011: 定时任务管理（Schedule）独立模型

## 状态

Accepted

## 背景（Context）

任务中心（`task_record` / `task_log`）只覆盖**一次性执行入队**：用户或插件提交、
队列调度、状态机流转、日志与重试。平台缺乏**周期性触发**能力——现有
`@Scheduled`（HostMonitorTask / TaskCleanupScheduler 等）全部是主应用硬编码，
插件无法定义或新建定时任务（如"每天凌晨爬一次地图"、"每周清理过期数据"）。

需求：参考执行队列的任务列表实现定时任务管理，并提供 API 给插件方
定义（声明默认计划）或新建（运行时 CRUD）定时任务。

核心分叉：到点触发后的行为是**向执行队列提交一个普通 Task**（复用任务中心
全部设施），还是**独立一套模型**？本决策选择后者——定时触发的执行语义
（无人工提交、无互斥排队、无自动重试）与任务中心的设计假设差异过大，
强行复用会在 task_record 上堆出 `triggerSource` / `scheduleId` 等特例字段，
并让互斥键、重试计数等语义在两套场景间互相污染。

## 决策（Decision）

### D1. 独立模型：不写 task_record，到点直接执行

定时任务体系与任务中心完全分离，仅**参考**其设计（状态机、来源隔离、
日志缓冲、清理调度），不复用其表、注册表与执行管道。术语上引入
**定时计划（Schedule）**（回答"什么时候做"）与 **定时触发记录（Schedule Run）**
（记录"做了什么"），与 Task（一次性入队执行）相对。

### D2. 三张宿主表，来源隔离镜像 task_record 模式

| 表 | 职责 |
|----|------|
| `scheduled_task` | 计划定义：key、名称、handler key、cron、payload 模板、enabled、source、plugin_id、下次触发时间 |
| `scheduled_task_run` | 触发记录：状态机 `RUNNING → SUCCEEDED / FAILED / CANCELLED / SKIPPED`（终态不可变），落 payload 快照，`MANUAL` 标记手动触发 |
| `scheduled_task_run_log` | run 执行日志，每 run 上限 500 条（对齐 task_log 体验） |

计划存宿主表（带 `source` / `plugin_id`）而非插件 ExtensionClient 自管表：
调度器是平台级能力，与任务中心同级；`task_record` 已有先例——插件任务存
宿主表、靠 source 隔离（符合 ADR-0002：`scheduled_task` 是核心表，非
`{gameCode}_*` 插件专属表）。主应用 UI 因此能统一列出全部插件的计划。

run 保留 30 天，挂到现有清理调度器旁执行。

### D3. ScheduledTaskHandler：全新独立执行契约

新扩展点 `ScheduledTaskHandler extends ExtensionPoint`（plugin 模块），插件
子容器内一个 `@Component` 即一个 Handler，由 `PluginSpringContextFactory`
扫描注册（与 `GameEnhancementExtension` 发现模式一致），按 `(source, key)`
注册。**不复用** TaskHandlerExtension 的包装注册与 TaskHandlerRegistry。

接口面极简：`getKey()` / `getDisplayName()` / `getDefaultTimeoutMs()`（默认
30 分钟）/ `execute(ctx, payload)`。不带生命周期钩子（onSuccess/onFailure/
onCancel 等由 Handler 在 execute 内 try/finally 自理）、不带互斥键、不带
重试声明——新契约第一版只加不删，宁小勿大。

### D4. 复用 TaskContext / TaskResult 接口类型

`execute` 的上下文与返回值沿用 plugin 模块现成的 `TaskContext` / `TaskResult`
**接口**——接口本身通用（log / isCancelled / isTimeout / reportProgress），
落点由实现决定：core 提供 run 版 `TaskContext` 实现（日志/进度写
`scheduled_task_run_log` / run 表，`getTaskId()` 返回 runId）。Handler 作者
代码形态与 TaskHandler 一致，学习成本为零；复用类型不等于耦合任务中心。

### D5. 双通道创建 + 稳定键 upsert，用户修改不被覆盖

插件获得定时能力的两条通道：

1. **声明式默认计划**：新扩展点 `ScheduledTaskDeclarationExtension`
   `getScheduleDeclarations()` 返回声明（`key / name / handlerKey / cron /
   payload / enabled`），插件加载时按稳定键 `pluginId:key` upsert 进
   `scheduled_task`。
2. **编程式 CRUD**：注入 `ScheduleService`（经 PluginSpringContextFactory
   注册进插件子容器），运行时新建/修改/启停/删除计划——实现内部按插件
   source 强制隔离，插件只能操作自己来源的计划（对齐 TaskService
   `cancelMyOwn` 哲学）。

upsert 冲突语义（防"插件重启覆盖用户修改"的经典坑）：**不碰用户改过的
cron / enabled**；用户物理删除的计划**不复活**（upsert 前检测删除标记）。

### D6. 触发语义：跳过、不补跑、无自动重试、手动触发

- **cron**：标准 6 位（Spring CronTrigger），服务器时区；前端可提供
  "每 X 小时"等预设生成表达式。
- **重叠跳过是计划级语义**：同计划上一轮 run 还在执行时，本次到点记一条
  `SKIPPED` run（原因"上一轮仍在执行"）后跳过；不同计划（即使引用同一
  handler）各自独立、允许并发。
- **停机不补跑**：平台重启后从下一个到点开始，错过的不补。
- **手动"立即执行"**：REST API 支持，产生 `MANUAL` 来源 run，不影响 cron
  下次触发；手动触发遇上一轮仍在执行，同 SKIPPED 语义。
- **无自动重试**：下一轮 cron 即天然重试；失败后仅支持手动重跑。Handler
  不存在（未注册/插件停用）时记 FAILED run，错误信息指明"处理器未注册"。
- **创建不校验注册表**：声明/新建只存 handler key（插件加载顺序不确定），
  触发时才解析。

### D7. 调度器：动态注册 + DB 持久化，启动全量重载

`ScheduledTaskRegistry` 统一管理计划生命周期（注册 / 取消 / 重调度）：
计划增删改时对 `ThreadPoolTaskScheduler` 做 cancel + reschedule；启动时从
`scheduled_task` 全量重载 enabled 计划。专用线程池（默认 4 线程，可配置），
与任务中心执行线程池隔离。单机 SQLite，无需分布式锁。

### D8. 生命周期：插件暂停计划，卸载删计划

- 插件 **stop / reload** → 其计划自动暂停（记暂停原因），Handler 随子容器
  注销；重新加载后恢复原 enabled 状态，并走 D5 upsert（保留用户修改）。
- 插件**卸载删除** → 其计划一并删除（对齐 PF4J 卸载 purgeTasks 语义）。
- **禁用**计划 = 只停未来触发，进行中的 run 跑完；**删除**计划 = 取消
  进行中 run。
- 主应用 core 自身可注册 source=MAIN 的 handler 与计划（core Spring Bean
  直接入表）；本次**不迁移**现有 `@Scheduled` 任务。

### D9. 管理 REST 与前端形态

- REST：`/api/schedules`（分页列表 / 改 cron / 启停 / 删除 / 立即执行）+
  run 历史子资源（分页 + 状态筛选 + 日志查看）。
- 前端：任务中心页新增「定时计划」tab（与"执行队列"并列）；列表列 =
  名称 / cron / handler / 来源 / 状态（启用 | 暂停 | 禁用）/ 上次结果 /
  下次触发时间；行展开查看 run 历史。计划**无** scopeType / instance 绑定
  字段，payload 自由携带 instanceId（"每实例一个计划"就建多条计划）。

## 后果（Consequences）

**正面：**

- 插件获得完整定时能力（声明默认 + 运行时 CRUD），且用户拥有最终控制权。
- 任务中心模型零污染，两套语义各自演进互不拖累。
- 执行历史、日志、跳过可见性齐全，排障体验对齐任务中心。
- 独立契约接口面小，插件学习成本低（execute 形态与 TaskHandler 一致）。

**负面 / 风险：**

- 三张新表 + 新扩展点两个 + 新 Service/Registry/Controller，一次性成本较高。
- "计划引用的 handler 可能不存在"成为常态（创建不校验），错误延迟到触发时
  才暴露，依赖 FAILED run 的错误信息引导排障。
- 同一 Handler 可被多计划并发触发（计划级跳过），插件 Handler 需自行保证
  对不同 payload 并发执行的安全（无状态要求与 TaskHandler 相同）。
- 与任务中心存在概念重叠（两套"任务"），依赖术语表区分 Schedule / Run / Task。

## 备选方案（Alternatives）

1. **到点提交普通 Task 到执行队列**（Q1 否决）：复用状态机/互斥/日志/重试
   零新建，但互斥键会让定时触发在上一轮未结束时排队而非跳过、自动重试与
   下一轮 cron 叠加、task_record 需加特例字段区分触发来源——执行语义冲突
   大于复用收益。
2. **Handler 复用 TaskHandler 与注册表**（Q10 否决）：插件零新增概念，但
   契约中 onSubmit/onRetry/getMutexKey/isRetryable 对定时场景全部无意义，
   实质是把独立语义塞进旧契约。
3. **计划存插件 ExtensionClient 自管表**（Q4 否决）：范围隔离更纯粹，但
   调度器要跨 ExtensionClient 抽象层读数据，主应用 UI 无法统一列出全部
   计划；task_record 的宿主表 + source 隔离先例更适合平台级能力。
4. **扫表调度（每 30s 轮询）**（Q6 否决）：实现更省心，但精度差、空转，
   且计划变更生效延迟；单机场景动态注册无协调成本。
5. **停机补跑 / 自动重试 / Handler 级互斥**（Q7/Q14/Q21 否决）：个人运维
   场景下补跑与重试弊大于利（爬虫堆两轮是灾难）；Handler 级互斥等于把
   mutexKey 概念请回独立模型。

## 术语

- **定时计划（Schedule）** / **定时触发记录（Schedule Run）** /
  **ScheduledTaskHandler**：见 [glossary.md](glossary.md)。
