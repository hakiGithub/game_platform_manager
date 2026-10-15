# 04 — 任务 payload 是 stringly-typed Map，契约只存在于注释与 ADR 正文

**Status:** needs-triage
**Type:** task
**来源:** 架构评审 2026-10-15（候选 4）

## 现象

每次 service → handler 交接都是一个无类型 `Map<String,Object>`，字段名/类型/必填性写在 Javadoc 与 ADR 正文里，
没有编译期约束让生产端与消费端吻合。

**同一个逻辑值 `instanceId`，读法不一致**：

| 消费端 | 行 | 读法 |
|---|---|---|
| `PatchInstallHandler` | :70 | `payload.getLong("instanceId")` |
| `CloudInstallService` | :173-179 | `payload.getLong("instanceId")` |
| `MapUploadTaskHandler` | :64-66 | `Long.parseLong(payload.getString("instanceId"))` |

生产端同样不一致：`CloudInstallService:117-142` 放 `Long`，`MapService:314-321` 放 `String`。

## 根因

`TaskPayload` 是 `Map<String,Object>`，契约由各 Handler 自行约定。票 01（headers/includePattern 被静默丢弃）
正是这套契约的必然产物——一个 field 可以无声消失，两侧无人察觉。

## 修复建议

按任务族定义类型化 payload（`PatchInstallPayload`、`CloudInstallPayload` 等），
由一个 codec 负责两侧序列化/反序列化；提交接口收 spec 对象而非 Map：

```
submit(taskType, source, scope, SPEC spec)   // 框架把 SPEC 落成 JSON
execute(context, SPEC spec)                  // handler 拿到类型化 spec
```

## 验收

- 至少一个任务族（PATCH_INSTALL）完成类型化，含往返 codec 单测。
- 消除 `getString`/`getLong` 类型漂移。
- 生产端与消费端字段不一致时编译期或测试期即失败。

## 关联

- **前置**：票 01 已把 PATCH_INSTALL 的字段清单收敛为一处，可先落地。
- ADR-0006 / ADR-0018 / ADR-0025 目前以正文叙述 payload 键名；
  若推进本票，需同步一份契约说明，否则 ADR 与实现会再次各执一词。
