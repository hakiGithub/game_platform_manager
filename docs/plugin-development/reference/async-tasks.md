# 任务处理器（TaskHandler）

> 对齐版本：v3.1.0（ADR-0001）｜ 权威源：`backend/plugin/` 源码

任务中心提供统一的异步任务提交、执行、监控、重试能力（ADR-025）。插件通过实现 `TaskHandlerExtension` 注册处理器，通过注入 `TaskService` 提交任务。

## 1. 核心接口

| 接口 | 职责 |
|---|---|
| `TaskHandler` | 任务处理器，定义执行逻辑与生命周期钩子 |
| `TaskHandlerExtension` | 扩展点入口，将 Handler 注册到任务中心（`@Component`） |
| `TaskService` | 任务服务，提交/查询/取消任务（注入子容器） |
| `TaskContext` | 执行上下文，进度上报、取消/超时检查、日志 |
| `TaskPayload` | 任务参数载体，类型安全 Map 访问 |
| `TaskResult` | 执行结果，封装输出数据与状态 |

## 2. 注册 Handler

```java
@Component
public class MyTaskHandlerExtension implements TaskHandlerExtension {
    private final Map<String, TaskHandler> handlers;
    public MyTaskHandlerExtension(CrawlTaskHandler crawl, BackupTaskHandler backup) {
        this.handlers = Map.of("crawl", crawl, "backup", backup);  // 构造时缓存
    }
    @Override public Map<String, TaskHandler> getTaskHandlers() { return handlers; }
}
```

> 任务来源（source）由框架自动填充为 gameCode 大写（如 `L4D2`），无需插件指定。Handler 必须无状态，状态通过 `TaskContext` 传递。

## 3. 实现 Handler

```java
@Component
@RequiredArgsConstructor
public class CrawlTaskHandler implements TaskHandler {
    private final OrangetageCrawler crawler;

    @Override public String getType()             { return "crawl"; }
    @Override public String getDisplayName()      { return "地图爬取"; }
    @Override public boolean isRetryable()        { return true; }      // 幂等允许重试
    @Override public int getMaxRetryCount()       { return 3; }
    @Override public long getDefaultTimeoutMs()   { return 30 * 60 * 1000L; }

    @Override
    public void onSubmit(TaskSubmitContext ctx) {  // 提交前校验，抛异常阻止提交
        String t = ctx.getPayload().getString("crawlType", "FULL");
        if (!"FULL".equalsIgnoreCase(t) && !"INCREMENTAL".equalsIgnoreCase(t))
            throw new IllegalArgumentException("无效的爬取类型: " + t);
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        context.reportProgress(0, "开始抓取");
        for (int page = 1; page <= totalPages; page++) {
            if (context.isCancelled())  return TaskResult.failure("任务已取消");
            if (context.isTimeout())    return TaskResult.failure("任务执行超时");
            context.reportProgress(page * 100 / totalPages, "已抓取 " + page + "/" + totalPages);
        }
        return TaskResult.success(Map.of("totalMaps", allItems.size()));
    }

    @Override
    public String getResultSummary(TaskResult result) {
        return result.isSuccess() ? "成功爬取 " + result.getData().get("totalMaps") + " 张地图" : result.getMessage();
    }
}
```

## 4. 提交任务

```java
@RequiredArgsConstructor
public class MapCenterService {
    private final TaskService taskService;
    public String triggerCrawl(String crawlType) {
        return taskService.submit(TaskSubmitRequest.builder()
            .taskType("crawl").source("L4D2").scopeType("GLOBAL")
            .payload(Map.of("crawlType", crawlType)).build());
    }
}
```

> 插件只能提交和取消自己来源的任务。

## 5. 进度上报节流（ADR-014）

`reportProgress` 内部已节流，可高频调用：相同 percent 忽略；不同 percent 距上次写入 <1s 仅更新内存；≥1s 或 percent=100 或终态强制刷盘。

## 6. 取消与超时（ADR-009 混合模式）

循环中定期检查 `context.isCancelled()` / `context.isTimeout()`，命中即返回 `TaskResult.failure`。超时阈值后再等 30s grace period，仍不结束则 `Future.cancel(true)` 强制中断，状态置 FAILED。

## 7. 互斥键（getMutexKey）

| 返回值 | 含义 |
|---|---|
| `null` | 默认：scopeKey 非空按 (taskType, scopeKey) 互斥；为空按 (source, taskType) 互斥 |
| `"backup:host1+instance1"` | 自定义键，相同键互斥 |
| `""` | 完全不互斥 |

## 8. TaskPayload 类型安全访问

```java
String  crawlType  = payload.getString("crawlType", "FULL");
Integer limit      = payload.getInteger("limit", 100);
Long    instanceId = payload.getLong("instanceId");
Boolean force      = payload.getBoolean("force", false);
```

> payload 序列化上限 64KB（ADR-026）；`TaskResult` 数据上限 256KB；日志每任务最多 500 条（ADR-010/023）。

## 9. 生命周期钩子顺序

```
submit() 线程：  └─ onSubmit()              提交前校验
executeAsync()： ├─ onBeforeExecute() → execute() → onAfterExecute() → onSuccess/onFailure/onCancel
retry() 线程：   └─ onRetry()               重试前校验
```

## 10. maxRetryCount 选取

| 任务类型 | 建议 | 理由 |
|---|---|---|
| 爬取/导出/状态查询（幂等） | 3 | 无副作用 |
| 部署/备份（有副作用） | 1 | 容器/文件可能已部分写入 |

> 约束：不要在 `execute` 吞掉 `InterruptedException`；不要在 `finally` 调 `reportProgress`（终态已刷盘）；Handler 必须无状态。
