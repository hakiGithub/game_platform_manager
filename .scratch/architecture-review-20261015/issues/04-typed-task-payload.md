# 04 — 任务 payload 是 stringly-typed Map，契约只存在于注释与 ADR 正文

**Status:** resolved
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

## Answer

- 已在插件 SDK 增加 `TaskPayloadCodec<T>`、`TypedTaskSubmitRequest<T>` 与
  `TypedTaskHandler<T>`。生产者通过 codec 提交 spec，Handler 通过同一 codec
  恢复类型化对象；原有 `TaskSubmitRequest.payload` / `TaskHandler.execute(...TaskPayload)`
  保持不变，现有未迁移任务类型继续工作。
- `PATCH_INSTALL` 是首个完整迁移的任务族：`PatchInstallContract` 现在是
  `TaskPayloadCodec<PatchInstallRequest>` 的唯一实例，`PatchInstallServiceImpl`
  和 `PatchInstallHandler` 都复用 `PatchInstallContract.INSTANCE`，不再各自读写
  字符串键。
- `PatchInstallContractTest` 覆盖全部 7 个字段经 JSON 往返；codec 的泛型把 spec
  类型与 encode/decode 绑定为编译期约束，字段拼接只保留在契约实现内。
- 未新增新的 ADR；ADR-0006/0018/0025 的 payload 语义未改变，实现入口由本票的
  codec 契约统一，后续字段变更以 `PatchInstallContract` 为准。
- 定向测试：SDK 7 条、PATCH_INSTALL 契约 2 条均通过；全量受影响模块编译通过。
