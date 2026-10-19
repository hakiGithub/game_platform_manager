# 06 — 子任务跟随与进度窗口在两个 service 里逐字重复

**Status:** resolved
**Type:** task
**来源:** 架构评审 2026-10-15（候选 6）

## 现象

「跟随一个已提交的子任务」这段握手在两个 service 里各写一遍：

| 位置 | 内容 |
|---|---|
| `CloudInstallService:426-458` | `pollPatchTask` + `Thread.sleep(2000)` |
| `DownloadService:432-476` | 同名方法，同 4 状态 switch，同 sleep |

两处都实现「每 2s 查 `taskService.getTask(id)`，把 PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
映射到本地状态与进度」。差异只在写往哪里（VO vs `RecordUpdater`）。

进度窗口换算第三次重复于 `MapUploadTaskHandler:181-219`（0-100 折进 `[startPercent, endPercent]`）。

## 根因

plugin SDK 的 `TaskService` 有 `getTask(String)`，但**没有「跟随」这一等能力**。
于是每个用到它的 service 各自实现轮询循环、状态映射与进度换算。

**取消语义还不一致**：`CloudInstallService` 抛 `CancelledSignal`，`DownloadService` 只同步终态。

## 修复建议

SDK 提供 `ChildTaskFollower`：

```
follow(childTaskId, ProgressWindow(base, slice), TaskContext, ChildOutcome) → void
```

内部承担轮询节奏、状态映射、取消传播与进度窗口换算。两个 service 删掉各自的循环。

## 验收

- 插件 SDK 暴露 `ChildTaskFollower`，两个 service 不再有 `pollPatchTask`。
- 轮询间隔与任务状态词只存在一处。
- 取消语义收敛为一种；用假 `TaskService` 可直接单测跟随逻辑。

## 关联

`TaskVO.getStatus()` 的字符串状态词（`"PENDING"` 等）散落在这些循环里，
若未来任务中心状态词变更，本票能一并收口。

## Answer

- 插件 SDK 新增 `ChildTaskFollower`、`ProgressWindow`、`ChildTaskOutcome`、
  `ChildTaskProgress`、`ChildTaskCancellation` 与统一的
  `ChildTaskCancelledException`。轮询间隔（2 秒）、状态词解释、进度窗口换算、
  子任务取消与失败结果处理都只在该 follower 中实现。
- `CloudInstallService` 与 `DownloadService` 的本地轮询循环已删除，服务只保留
  `followPatchTask` 适配器，把 follower 的进度/结果同步到各自的 VO 或
  `RecordUpdater`。`MapUploadTaskHandler` 也改用 `ProgressWindow` 映射传输进度。
- 取消语义统一为 `ChildTaskCancelledException`：父任务取消/子任务取消会尽力取消
  子任务并抛该异常；主任务执行器将其映射为 `CANCELLED`。超时仍保持既有契约，
  通过 `ChildTaskTimeoutException` 映射为 `FAILED`，不会破坏 ADR-009。
- `ChildTaskFollowerTest` 使用假 `TaskService` 覆盖 PENDING/RUNNING 进度映射、
  COMPLETED、FAILED、CANCELLED、父任务取消与轮询节奏；`TaskServiceIntegrationTest`
  覆盖取消与超时的执行器终态映射。定向测试 44 条全部通过（SDK 7、核心 23、
  L4D2 14，核心含契约测试）。
