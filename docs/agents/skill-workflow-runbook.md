# Skill 使用指南与并行 Runbook

本文面向本项目维护者与 agent。上游 skill 的完整说明以安装后的 `SKILL.md` 为准；这里只记录选择入口、本仓库约定和并行执行办法。

## 主线与首次使用

从想法到交付的主线是：`/grill-with-docs` → `/to-spec` → `/to-tickets` → `/implement-spec` 或 `/implement` → `/code-review` → `/pr` → `/retro`。前三步留在同一段上下文中；每个实现票使用新的上下文。

首次使用短路径：

1. 在仓库根目录运行 `/grill-with-docs <想法>`，把目标、边界和验收问清楚。涉及长期决策时写入 `docs/design/adr/`，术语写入根目录 `GLOSSARY.md`。
2. 运行 `/to-spec` 生成或返回 spec，检查测试 seam 与 scope。
3. 运行 `/to-tickets <spec-path>`，确认每票可独立验证、`Blocked by` 只保留真实前置。
4. 只有一票或一个小改动时，运行 `/implement <ticket-path>`；需要整份 spec 并行时，转到下面的 runbook。
5. 实现完成后以固定起点运行 `/code-review <fixed-point>`；提交 PR 前运行 `/pr`，会话收尾运行 `/retro`。

## 选择 Skill

| Skill | 适用场景 | 本仓库入口或注意点 |
| --- | --- | --- |
| `ask-matt` | 不确定该用哪条流程、需要从 skill 入口中选择 | 先读被推荐 skill 的 `SKILL.md`，不要只凭摘要执行 |
| `grill-with-docs` | 想法模糊，需要通过访谈收敛；同时维护术语和 ADR | 术语写 `GLOSSARY.md`，决策写 `docs/design/adr/` |
| `to-spec` | 对话和代码探索已经足够，需要固化需求、seam、scope | spec 发布到 `.scratch/<feature>/spec.md` |
| `to-tickets` | 把 spec 拆成独立纵向切片并声明依赖 | 一票一文件，写到 `.scratch/<feature>/issues/NN-<slug>.md` |
| `implement` | 单票、小改动，或希望逐票手动推进 | 从 ticket 开始，使用 fresh context；完成前跑 `/code-review` |
| `implement-spec` | 整份 spec 已拆票，需要按 task graph 并行 frontier | 每票独立 worktree 和 branch，统一合并到 integration branch |
| `tdd` | 有明确 seam，希望先建立失败测试再实现 | 作为 `/implement` 的内部实现方式，不必绕开主线单独使用 |
| `code-review` | 需要从固定 commit/branch 做 Standards 与 Spec 双轴审查 | 调用时提供 fixed point；缺省起点会要求补充 |
| `pr` | 需要编写 PR body | 使用 Summary、Evidence、Merge Danger 结构，引用实际验证结果 |
| `retro` | 一轮实现结束后改进 agent 环境 | 产出是环境改进候选，不替代代码修复或 code review |
| `triage` | 处理外来 issue/PR 的分诊、复现和 agent brief | 本项目 `to-tickets` 生成且已 ready 的票不再 triage |
| `diagnosing-bugs` | 难复现、偶发或性能回归，需要先建立红灯反馈回路 | 先建立 tight feedback loop，再提出和验证修复假设 |
| `wayfinder` | 工作大到跨多个 session，且路线仍有雾区 | 先产出决策地图，清雾后回到 `/to-spec`，不直接把它当实现队列 |
| `improve-codebase-architecture` | 功能之外，寻找可加深的模块、seam 和测试面 | 产出候选后选择一个进入 `/grill-with-docs` 主线 |

`resolving-merge-conflicts` 已下线。merge 或 rebase 出现冲突时，agent 直接检查当前 git 状态、双方提交意图和可运行的自动化检查，按最新事实解决；若冲突涉及业务语义且证据不足，暂停并请维护者裁决，而不是机械选择一侧。

## Local Tracker

本仓库使用 `.scratch/<feature-slug>/` 的本地 Markdown tracker：

- `spec.md` 保存该 feature 的需求与验收；`README.md` 可选，用于导航。
- `issues/NN-<slug>.md` 一票一文件，编号从 `01` 开始；票文件顶部写 `Status:`，依赖写在 `Blocked by:`。
- `Status: ready-for-agent` 表示票已可领取；`Blocked by: None` 或为空表示没有前置；列出的票都 `resolved` 后，该票才进入 ready frontier。
- frontier 是“开放、无未完成前置、尚未被领取”的票。领取时先把该票 `Status` 改为 claimed（或仓库现有等价状态），再做任何修改，避免两个 worker 同时处理。
- 完成时在票内记录结果或 `## Answer`，把 `Status` 改为 `resolved`，并按需要把上下文指针追加到 feature 的 `README.md`/map。不要用单独的汇总文件替代一票一文件。

第一次接触一个 feature 时，先读其 `README.md`、`spec.md` 和目标票；票里的 `Blocked by` 优先于编号顺序。

## 整 Spec 并行 Runbook

推荐做法是先把 task graph 和前置票做干净，再让无依赖票并行。

1. 读 `spec.md`、`README.md` 与全部 ticket，列出 frontend、backend、docs、test 等实际边界，确认每票都是可独立验证的纵向切片。
2. 建立 integration branch，并确保所有 worker 的 worktree 从它的当前 tip 开始；记录 base SHA。
3. 只领取 ready frontier。每个实现票使用独立 worktree/branch，不在主工作区共用未提交改动；worker 开始前先确认自己的 worktree 基于 integration tip。
4. 每票按 `/implement <ticket-path>` 推进，能测试就按 `/tdd` 建立红绿循环；完成后先合并 integration 当前 tip，再在自己的分支跑该票的测试、lint/typecheck 和必要构建。
5. 实现票通过后由 merger 合入 integration branch，保留提交意图与验证证据；合并后重新计算 frontier，再启动新解锁的票。
6. 全部票完成后，在 integration branch 上以一个固定起点运行 `/code-review`。Review 发现的问题由一个实现 worker 修复，再重新验证。
7. 需要 PR 时在 integration branch 运行 `/pr`；最后逐票 resolved，清理临时 worktree，并运行 `/retro` 记录可改进的环境问题。

并行时共享文件是主要冲突源。ticket graph 中的 A/B 两票若必须修改同一契约、同一 lock 文件或同一术语源，应通过 `Blocked by` 串行；不要靠合并后的手工修补掩盖依赖缺失。

## `/implement` 还是 `/implement-spec`

单票或小改走 `/implement`：它从 ticket 开始，适合 fresh context，一个 agent 可以完整持有目标、失败信号、改动和 review。整份 spec 的并行 frontier 走 `/implement-spec`：它把 ticket 当 task graph，多个 implementer 各自使用 worktree，最后由 integration branch 和一次全局 code review 收口。

选择标准是上下文和协调成本，而不是票数本身。若票之间需要实时共享设计判断，先补依赖或缩小并行面；若每票都能独立验证，且你希望同时利用多个 worker，使用 `/implement-spec`。

## 更新与漂移检查

上游 skill 的权威内容不在本指南里。更新入口是仓库根目录的 `skills-lock.json` 与固定 commit 的上游 `mattpocock/skills`；项目级 `.agents/skills/` 是共享副本，`.claude/skills/` 跟随同步，自定义平台 skill 保持独立。

更新或怀疑漂移时，先读取 `skills-lock.json` 的目标 commit 与路径，再从该 commit 核对 `SKILL.md` 和附属文件；最后用以下只读检查确认仓库入口与文档路径仍可解析。不要用浮动 `main` 覆盖本地副本，也不要在本指南复制上游 skill 全文。

```powershell
$feature = '<feature-slug>'
$spec = ".scratch/$feature/spec.md"
$tickets = ".scratch/$feature/issues"
Test-Path -LiteralPath $spec
Test-Path -LiteralPath $tickets
Resolve-Path -LiteralPath 'docs/agents/skill-workflow-runbook.md'
Resolve-Path -LiteralPath 'docs/design/adr/README.md'
if (Test-Path -LiteralPath 'GLOSSARY.md') { Resolve-Path -LiteralPath 'GLOSSARY.md' }
git rev-parse --show-toplevel
Get-Content -Raw -LiteralPath 'skills-lock.json' | ConvertFrom-Json | Select-Object -ExpandProperty skills | Get-Member -MemberType NoteProperty | Select-Object -ExpandProperty Name
```

如果当前 worktree 还没有 ticket 文件，先从包含 `.scratch/<feature-slug>/` 的 worktree 读取 ticket 内容，再到目标 worker worktree 执行修改；路径检查必须针对实际存在的工作树，不能把主工作区的未提交业务文件带入当前 worktree。
