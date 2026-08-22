# 定时任务（ScheduledTask，ADR-0011）

> 对齐版本：v3.8.0（ADR-0011）｜ 权威源：`backend/plugin/` 源码 `com.gameplatform.plugin.schedule.*`

定时任务是一套**独立于任务中心**的按 cron 周期性执行体系（ADR-0011），插件可通过两条渠道定义计划，由宿主统一调度触发。与 `TaskHandler`（任务中心）**完全分离**：不复用其注册表、状态机与互斥键，无自动重试（下一轮 cron 即天然重试，失败后仅支持手动重跑）。

- 声明式默认计划：`ScheduledTaskDeclarationExtension`（随插件分发，升级可演进）
- 编程式动态计划：`ScheduleService`（运行时按用户/业务创建）

宿主数据模型：`scheduled_task` / `scheduled_task_run` / `scheduled_task_run_log` 三张表（与任务中心 `task_record` 无关联）。主界面「任务中心 → 定时计划」tab 统一管理所有来源计划。

## 1. 核心接口

| 接口 | 职责 |
|---|---|
| `ScheduledTaskHandler` | 定时任务处理器（插件子容器内一个 `@Component` 即一个 Handler） |
| `ScheduledTaskDeclarationExtension` | 声明式默认计划扩展点（`@Component`） |
| `ScheduleService` | 编程式计划服务（注入插件子容器，自动绑定本插件 source） |
| `ScheduleCreateRequest` / `ScheduleUpdateRequest` | 创建 / 更新请求 |
| `ScheduleDeclaration` | 声明式默认计划描述 |
| `ScheduleVO` / `ScheduleRunVO` / `ScheduleRunQuery` | 计划 / 触发记录 VO 与查询条件 |
| `TaskContext` / `TaskPayload` / `TaskResult` / `TaskLog` | 复用任务中心的执行上下文模型（run 版） |

## 2. 三张表与状态机

### scheduled_task（计划）

| 字段 | 说明 |
|---|---|
| `id` | String 雪花 ID |
| `source` | 来源（大写 `MAIN` / `{gameCode}`） |
| `pluginId` | 插件 ID（`MAIN` 来源为 null） |
| `handlerKey` | 触发时按 `(source, handlerKey)` 解析 Handler |
| `enabled` | 用户启用意图：1 启用 / 0 禁用 |
| `paused` | 系统暂停（如插件停用）：1 暂停 / 0 正常 |
| `declarationKey` | 声明稳定键 `pluginId:key`（声明式计划才有，upsert 定位用） |
| `userModified` | 用户是否改过（1 则声明 upsert 跳过整行） |

状态组合：`enabled=1 + paused=0` 调度中；`enabled=1 + paused=1` 系统暂停；`enabled=0` 用户禁用。逻辑删除（`is_deleted`）同时充当声明式计划的"复活墓碑"：用户删除的计划，插件重载 upsert 时不再复活。

### scheduled_task_run（触发记录）

状态机：`RUNNING → SUCCEEDED / FAILED / CANCELLED / SKIPPED`（终态不可变）。
`SKIPPED` 含义：上一轮仍在执行时本轮跳过（同一计划禁止并发触发）。

### scheduled_task_run_log（执行日志）

每次触发最多记录 500 条；宿主每日 3:30 自动清理 30 天前的 run 记录与日志。

## 3. 声明式默认计划（写代码即分发）

插件实现 `ScheduledTaskDeclarationExtension` 并标 `@Component`，插件加载时宿主按稳定键 `pluginId:key` upsert 进 `scheduled_task` 表：

```java
@Component
public class L4D2ScheduleExtension implements ScheduledTaskDeclarationExtension {
    @Override
    public List<ScheduleDeclaration> getScheduleDeclarations() {
        return List.of(ScheduleDeclaration.builder()
                .key("dailyMapCrawl")          // 稳定键，同插件内唯一
                .name("每日地图增量爬取")
                .handlerKey("mapCrawl")        // 须与本插件注册的 ScheduledTaskHandler.getKey() 一致
                .cron("0 0 4 * * ?")           // 标准 6 位 Spring 语法，服务器时区
                .payload(Map.of("crawlType", "increment"))
                .enabled(true)                 // 缺省 true
                .build());
    }
}
```

### upsert 冲突语义（防"插件重启覆盖用户修改"）

| 场景 | 行为 |
|---|---|
| 用户改过的计划（`userModified=1`） | 整体跳过，不覆盖（保留用户设置） |
| 用户删除的计划（`is_deleted=1` 墓碑） | 不复活 |
| 未修改过的计划 | 随声明演进更新（name / cron / payload / handlerKey） |
| key 重复 | 抛 `IllegalStateException`（同插件内须唯一） |

## 4. 注册 Handler

插件子容器内一个 `@Component` 即一个 `ScheduledTaskHandler`，由 `PluginSpringContextFactory` 扫描后按 `(source, key)` 注册到 `ScheduledTaskHandlerRegistry`（source 为插件 gameCode 大写）：

```java
@Component
public class MapCrawlScheduleHandler implements ScheduledTaskHandler {
    private final MapCenterService mapCenterService;

    @Override public String getKey()         { return "mapCrawl"; }
    @Override public String getDisplayName() { return "地图定时爬取"; }
    // default getDefaultTimeoutMs() = 30 分钟；0 表示不超时

    @Override
    public TaskResult execute(TaskContext ctx, TaskPayload payload) throws Exception {
        String crawlType = payload.getString("crawlType", "increment");
        ctx.log("开始爬取，类型=" + crawlType);          // 写 run 日志表
        for (...) {
            if (ctx.isCancelled()) return TaskResult.failure("已取消");
            if (ctx.isTimeout())   return TaskResult.failure("超时");
            ctx.reportProgress(pct, "已抓取 x/y");      // 已节流
        }
        return mapCenterService.doCrawl(crawlType);
    }
}
```

> Handler **必须无状态**（状态经 `TaskContext` 传递）；不要吞 `InterruptedException`，应恢复中断标志并退出。同一 Handler 可被多个计划引用，不同计划触发可**并发**执行，需对不同 payload 并发安全。成败后的清理在 `execute` 内 try/finally 自理（定时契约无 onSubmit/onSuccess 等生命周期钩子）。

**关键差异**（对照任务中心 `TaskHandler`）：
- key 由 `getKey()` 提供（非 `getType()`），同 source 内唯一
- 创建计划时**不校验** handlerKey 是否注册，触发时才解析；未注册则记 `FAILED` run
- 无 `isRetryable` / `getMaxRetryCount` / `getMutexKey` / `onSubmit`——不重试、不互斥

## 5. 编程式创建计划（运行时）

注入 `ScheduleService`（插件子容器自动注册的代理，内部绑定本插件 `source` + `pluginId`）。**所有操作强制本来源隔离**：插件无法创建/修改/删除/触发其他来源的计划，查询也仅返回本来源。

```java
@RequiredArgsConstructor
public class MapCenterService {
    private final ScheduleService scheduleService;

    public String createDailyCrawl(String crawlType) {
        return scheduleService.create(ScheduleCreateRequest.builder()
                .name("每日增量爬取")
                .handlerKey("mapCrawl")
                .cron("0 0 4 * * ?")
                .payload(Map.of("crawlType", crawlType))
                .enabled(true)                 // 缺省 true
                .build());                     // 返回计划ID
    }
}
```

### ScheduleService 方法

| 方法 | 说明 |
|---|---|
| `create(request)` | 创建计划，返回 ID（source/pluginId 自动填充，插件无法伪造） |
| `update(id, request)` | 更新 name / cron / payload（仅本来源） |
| `enable(id)` / `disable(id)` | 启停（disable 只停未来触发，进行中的 run 跑完） |
| `delete(id)` | 逻辑删除（取消进行中的 run；声明式计划插件重载后不复活） |
| `trigger(id)` | 立即触发一次 → MANUAL 来源 run，返回 runId；遇上一轮在跑记 `SKIPPED` |
| `get(id)` / `list(query)` / `listRuns(query)` / `getRunLogs(runId)` | 查询（仅本来源；run 日志最多 500 条） |

> 更新仅允许改 `name / cron / payload`；`enabled` 走独立启停接口；`handlerKey` 创建后不可变（变更处理器语义应删除重建）。

## 6. 触发与并发

- cron 由宿主 `ThreadPoolTaskScheduler` 动态注册与重调度；**停机后不补跑**（不 catch-up）。
- 同一计划**同一时刻只允许一个 run 在执行**：到点了上一轮仍在跑 → 本轮跳过，记 `SKIPPED`（非 FAILED）。
- 超时协作式流程与任务中心一致：先置 timeout 标志由 Handler 循环中检查主动退出，超时阈值后再等 30s grace period 强制中断。

## 7. 插件生命周期联动

| 事件 | 宿主行为 |
|---|---|
| 插件加载/重载 | 扫描注册 Handler + 声明 upsert；恢复之前暂停的计划 |
| 插件停用/热重载（`purgeTasks=false`） | 暂停本插件全部计划（`paused=1`，保留数据） |
| 插件卸载移除（`purgeTasks=true`） | 取消进行中 run → 级联删日志/记录 → 物理删计划 |

> 热部署 `scripts/deploy-plugin.sh` 默认 `purgeTasks=false`，计划**暂停而非删除**，重载后恢复，历史 run 保留。彻底卸载（移除插件）才物理清理。

## 8. 常见陷阱

| 症状 | 先查 |
|---|---|
| 计划从不触发 | cron 是否合法（6 位 Spring 语法）；`enabled`/`paused` 状态；Handler 是否已注册（`getKey()` 与计划 `handlerKey` 一致） |
| 触发即 `FAILED` | run 的 `errorMessage`；handlerKey 未注册 / Handler 异常 |
| 声明式计划被"复活"或"覆盖" | `userModified` / `is_deleted` 墓碑语义——用户改过的删过的不受声明影响 |
| 插件热重载后计划消失 | 是否用了 `purgeTasks=true`（仅移除插件该用，热部署不该用） |
| 期望并行触发却 `SKIPPED` | 同一计划同一时刻只允许一个 run（设计如此），通过多计划引用同一 Handler 并行 |