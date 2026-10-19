# 01: 固定上游 commit 并同步项目级 skill 副本

Status: claimed
Type: task
Blocked by: None

## 要交付的行为

项目内 `skills-lock.json` 指向确定的 `mattpocock/skills` commit，`.agents/skills/` 与 `.claude/skills/` 中的上游来源 skill 与该 commit 一致；自定义平台 skill 保持原样，新的三个工程 skill 可用，已删除的 skill 不再出现在锁文件与项目副本中。

## 验收

- [ ] 锁文件记录上游 commit `49dd158d1076134a641b33efb035946536778336` 对应的 27 个 `engineering` / `productivity` skill。
- [ ] 25 个已有 skill 的 `SKILL.md` 与外置引用文件刷新到该 commit。
- [ ] 新增 `implement-spec`、`pr`、`retro`，包含各自的同目录附属文件。
- [ ] `resolving-merge-conflicts` 从锁文件、项目级副本与 agent 映射中移除。
- [ ] `game-platform-dev`、`gameplatform-e2e`、`gameplatform-plugin-dev` 未被覆盖。
- [ ] `.agents/skills/<name>/SKILL.md` 与 `.claude/skills/<name>/SKILL.md` 对全部上游来源 skill 内容一致。
- [ ] 更新过程不依赖浮动的 `main`；可重复得到同一版本。
- [ ] 记录本次使用的同步方式；当前 Node 低于 `skills@1.7.2` 要求，优先固定 commit 的 git clone 或先升级 Node。

## 实现提示

上游 1.3.1 的目标范围只是：

- `skills/engineering/*`
- `skills/productivity/*`

不要安装 `in-progress`、`misc`、`deprecated`。自定义 skill 目录以项目现有副本为权威。

## 完成证据

- lock diff。
- 目标 skill 数量与名称清单。
- 副本一致性检查输出。
- 自定义 skill 未被改动的前后 hash。
