# 09 — ChildTaskFollower 遇到 UNKNOWN 状态会无限轮询

**Status:** resolved
**Type:** task
**来源:** Arch 04/06 实施后复核

## 现象

`ChildTaskFollower.follow(...)` 对无法识别的任务状态进入 `UNKNOWN` 分支后只继续循环，
既不设置上限，也不返回失败。若任务中心返回新状态词、空状态或格式异常，
父任务会每 2 秒无限查询下去，直到外层任务被取消或超时。

## 根因

状态词解释只覆盖当前已知的 `PENDING/RUNNING/COMPLETED/FAILED/CANCELLED`；
`UNKNOWN` 被当作“瞬时状态继续重试”，但没有连续次数或时间边界。

## 修复建议

为连续 `UNKNOWN` 设置明确上限（例如连续 3 次），超过后返回
`ChildTaskOutcome.failed("子任务状态未知")`，并补回归测试覆盖：

1. 连续 `UNKNOWN` 达到上限即失败，不再继续轮询；
2. 中间恢复正常状态时计数重置，不误判；
3. `null` 任务或缺失状态同样纳入 `UNKNOWN` 上限。

## 验收

- [x] `UNKNOWN` 不再造成无界轮询。
- [x] 假 `TaskService` 可稳定复现并验证上限行为。
- [x] 现有跟随状态、取消、超时测试继续通过。

## Answer

- 连续 `UNKNOWN`（包括任务不存在或状态缺失）最多重试 3 次；达到上限后返回
  `ChildTaskOutcome.failed("子任务状态未知")`。
- 任一已知状态出现时重置连续计数，避免瞬时未知状态在后续恢复正常时被误判。
- `ChildTaskFollowerTest` 新增连续未知、计数重置和任务不存在三类回归用例。
