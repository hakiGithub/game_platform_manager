# Spec: Matt Pocock Skills 刷新与项目接入

Status: ready-for-agent
Type: task
来源: 2026-10-19 上游 `mattpocock/skills` 版本核对

## Problem Statement

当前仓库通过根目录 `skills-lock.json` 固定了 `mattpocock/skills` 的 skill 来源，本地项目副本位于被忽略的 `.agents/skills/` 与 `.claude/skills/`。锁文件仍指向旧版本，25 个已安装 skill 的 `SKILL.md` 都与上游 `main` 不一致；上游 1.3.1 还新增了 `implement-spec`、`pr`、`retro`，移除了 `resolving-merge-conflicts`，并把领域文档约定从 `CONTEXT.md` 改为 `GLOSSARY.md`。

这会让后续 agent 读到过期流程：尤其是无法使用新的并行 ticket 执行器、PR 收尾规则和 session 复盘流程。同时，仓库现有 `CONTEXT.md` 与上游新版 skill 的 `GLOSSARY.md` 约定不一致，若不收口，领域词汇会分裂成两套。

## Solution

把仓库内可追踪的 skill 锁文件更新到上游固定 commit，并同步项目级 skill 副本；保留仓库自定义 skill 不动。采用上游 `GLOSSARY.md` 命名，同时为现有 `CONTEXT.md` 引用提供兼容迁移。新增一份中文使用指南，明确主线流程、并行 ticket 执行方式、各 skill 的适用场景与本仓库已有的 `docs/agents/` 配置。

最终状态：`skills-lock.json` 可复现上游 1.3.1 的工程与生产力 skill 集合；`.agents/skills/` 是项目级权威副本；`.claude/skills/` 与其它 agent 目录跟随同步；自定义 `game-platform-dev`、`gameplatform-e2e`、`gameplatform-plugin-dev` 不受影响；使用者能按指南选择 `/implement` 或 `/implement-spec`。

## Upstream Baseline

- Repository: `https://github.com/mattpocock/skills`
- Pinned commit: `49dd158d1076134a641b33efb035946536778336`
- Version: `1.3.1`
- Upstream last push: 2026-10-13
- Target skill set: `skills/engineering` 20 个 + `skills/productivity` 7 个，共 27 个
- Note: GitHub API `contents?ref=main` 与固定 commit 的实际目录树曾出现移动/删除路径差异。实施时以 commit tree 和该 commit 的 clone 为准，不混用 API 默认分支快照。

### 变更面

- 现有 25 个上游来源 skill 全部需要重新同步 `SKILL.md` 与同目录附属文件。
- `resolving-merge-conflicts` 在新版上游已删除；本项目保留它会造成锁文件与副本漂移。
- 新增 `implement-spec`、`pr`、`retro`。
- `implement-spec` 把 ticket 当作 task graph，跨 ready frontier 并行执行，统一落到 integration branch。
- 上游把 `CONTEXT.md` / `CONTEXT-MAP.md` 约定重命名为 `GLOSSARY.md` / `GLOSSARY-MAP.md`。
- 当前 Node `v22.18.0` 低于 `skills@1.7.2` 声明的 `>=22.20.0`，CLI 仍可运行但会告警；更新流程优先使用固定 commit 的 git clone，或在升级 Node 后再使用 CLI。

## User Stories

1. As a 平台维护者, I want `skills-lock.json` 指向一个明确的上游 commit, so that 每次重建环境都能得到同一批 skill。
2. As a 平台维护者, I want `.agents/skills/` 与 `.claude/skills/` 同步到同一版本, so that Codex 与 Claude Code 读到相同流程。
3. As a 平台维护者, I want 本地自定义 skill 保持不变, so that 上游更新不会覆盖平台专属开发规范。
4. As a 平台维护者, I want 新安装的 `implement-spec`, so that 我可以用 frontier 并行处理已拆分的 ticket。
5. As a 平台维护者, I want 新安装的 `pr`, so that PR 描述自然包含 Summary、Evidence、Merge Danger 三段。
6. As a 平台维护者, I want 新安装的 `retro`, so that 每轮实现后能把失败模式转化成环境改进。
7. As a 平台维护者, I want 删除已下线的 `resolving-merge-conflicts`, so that skill 列表不保留上游已废弃入口。
8. As an agent, I want 根目录术语文件使用 `GLOSSARY.md`, so that 上游新版 `domain-modeling`、`grill-with-docs`、`wait-what` 能自动读到领域词汇。
9. As a human maintainer, I want 旧 `CONTEXT.md` 路径仍能指向新术语表, so that 现有 ADR、旧 ticket 与历史文档不会形成死链接认知。
10. As a new contributor, I want 一份中文使用指南, so that 我知道从 idea 到 spec、ticket、实现、review、PR、retro 的顺序。
11. As a parallel executor, I want ticket 的 `Blocked by` 关系清楚, so that 我能只领取 ready frontier 上的票。
12. As a reviewer, I want 更新后的 skill 集合有可检查的 lock 与目录一致性, so that 后续漂移能在一个验证步骤里发现。

## Implementation Decisions

1. 以 commit `49dd158d` 为唯一更新基准，不跟随 `main` 浮动；锁文件记录该版本的每个 skill 路径与 `SKILL.md` SHA-256。
2. 只更新 `skills/engineering` 与 `skills/productivity` 中本项目已采用的 skill，以及新增的 `implement-spec`、`pr`、`retro`；不引入 `in-progress`、`misc`、`deprecated` bucket。
3. `.agents/skills/` 是共享的项目级权威副本；`.claude/skills/` 是 Claude Code 的镜像。同步后应验证公共 skill 内容一致。
4. 自定义 skill 属于本地平台知识，不参与上游覆盖：`game-platform-dev`、`gameplatform-e2e`、`gameplatform-plugin-dev`。
5. `resolving-merge-conflicts` 从锁文件和项目副本中移除；使用指南说明 merge/rebase 冲突改由 agent 直接按当前 git 状态处理最新。
6. `CONTEXT.md` 迁移为 `GLOSSARY.md` 作为唯一术语源。旧文件保留为简短兼容指针，避免仓库大量历史引用失效；新文件承载现有术语内容。
7. 更新 `docs/agents/domain.md`，把领域读取入口改为 `GLOSSARY.md`，并注明仓库 ADR 位于 `docs/design/adr/`。
8. 使用指南放在 `docs/agents/` 下，面向项目维护者和 agent，内容包括主线流程、并行执行、常用命令、本仓库特例、更新与验证方法。
9. 不在 `AGENTS.md` 重复整份使用手册；只在 `Agent skills` 区域加入最小指针，避免把大量低频操作塞进每个会话的上下文。
10. 实施优先由 `/implement-spec` 驱动本 spec 的 ticket graph；若只领取单票，则用 `/implement <ticket-path>`。

## Workflow Guidance To Capture

主线路径：

1. `$grill-with-docs`：把模糊想法问清楚，维护 `GLOSSARY.md` 与 ADR。
2. `$to-spec`：把已经谈清楚的内容固化成 spec。
3. `$to-tickets`：拆成每票一条纵向切片，写清 `Blocked by`。
4. `$implement-spec`：整份 spec 并行实现，frontier ticket 各自 worktree，统一合并到 integration branch。
5. `$implement`：只需要做一张票或一张小改动时使用。
6. `$code-review`：在固定点上做 Standards 与 Spec 双轴 review。
7. `$pr`：写 PR 时使用 Summary、Evidence、Merge Danger 结构。
8. `$retro`：session 结束后只改进 agent 环境，不拿它替代代码修复。

本仓库特例：

- Issue tracker 是 `.scratch/<feature>/` 下的 local markdown。
- `needs-triage` 等五角色标签已配置在 `docs/agents/triage-labels.md`。
- 领域术语使用 `GLOSSARY.md`，ADR 使用 `docs/design/adr/`。
- 更新 skill 本身不是业务改动，不触发后端或前端构建；验证重点是 lock、目录、可发现性与命令路径。

## Testing Decisions

- 测试外部可观察结果：锁定 commit、skill 名称集合、`SKILL.md` hash、命令可发现性和文档链接，不测试 CLI 内部实现。
- 用固定上游 clone 重新计算 hash，证明锁文件与副本来自同一 commit。
- 用 `npx skills list --json` 或等价目录扫描证明 27 个目标 skill 可见，且自定义 skill 仍在。
- 用 `rg` 检查 `CONTEXT.md` 的活跃说明，允许兼容指针与历史记录，不允许新的权威规则继续指向旧命名。
- 文档验收以“新维护者能按指南选对命令”为准；至少检查主线路径、并行路径、单票路径都有明确入口。

## Out Of Scope

- 安装上游 `in-progress`、`misc`、`deprecated` bucket 中的 skill。
- 改写上游 skill 内容或向上游提交补丁。
- 迁移业务代码、插件代码或 E2E 测试。
- 把 `.agents/` 与 `.claude/` 从 `.gitignore` 中取消忽略。
- 建立自动跟随 `main` 的定时更新；本 spec 只做可复现的固定版本刷新。

## Further Notes

第一批 frontier 为 ticket 01 与 ticket 03；ticket 02 依赖 01；ticket 04 依赖 01、02、03。用户要求先把 issue 单记录清楚，再后续并行处理，因此本轮只产出 spec 与 ticket 文件。
