# ADR-0014: 插件开发文档三副本职责分工

- 状态：Accepted
- 日期：2026-08-29
- 关联：[插件开发文档（面向人）](../../plugin-development/README.md)、[Glossary](glossary.md)

## 背景（Context）

插件开发文档物理上存在三份副本：

1. `docs/plugin-development/` —— 面向开源贡献者的人类文档（README + `reference/` + `examples/`）；
2. `.trae/skills/gameplatform-plugin-dev/` —— 工作区 AI skill（SKILL.md + `references/` + `examples/`）；
3. `~/.agents/skills/gameplatform-plugin-dev/` —— 用户级 AI skill（跨项目可用，AI 编码工具实际加载的副本）。

2026-08-23 的文档整理只规范化了 ①（文件名改连字符与语义命名：`persistence` / `async-tasks` / `scheduled-tasks` 等），②③ 未跟进；② 的 SKILL.md 头部写"与 docs 内容保持一致"、底部"文档维护约定"却写"本 SKILL 目录为唯一权威源"，两处自相矛盾，且"保持一致"已因文件名与内容漂移事实失效。按 halo-plugin-dev skill 范式重组 ②③ 时，必须先回答"谁是一致性锚点"。

## 决策（Decision）

1. **API 权威永远是 `backend/plugin/` 源码**——所有文档都是快照，签名冲突时以源码为准。
2. **职责分离**：`docs/plugin-development/` 是面向人的权威文档；AI skill 副本是自包含的程序性知识，允许与 docs 形态分化，只要求**概念一致**。
3. **skill 必须自包含，不做薄指针**：用户级副本在其他项目里读不到平台仓库的 `docs/`，"skill 只留索引、正文链接仓库 docs"的方案被否决。
4. **两处 skill 副本逐字同步**：工作区 `.trae/skills/gameplatform-plugin-dev/` 是编辑源，修改先落工作区，再整目录复制到用户级 `~/.agents/skills/gameplatform-plugin-dev/`；两处若漂移，以工作区为准重新复制。
5. **skill 文件名向 docs 对齐**（连字符 + 语义命名），降低未来同步的认知成本。
6. 废止"本 SKILL 目录为唯一权威源"与"内容与之保持一致"两类旧表述（原 SKILL.md、gotchas.md §14 等处）。

## 后果（Consequences）

- skill 与 docs 内容/形态不一致属**预期行为**，不要互相"纠正"；概念性修订（新增扩展点、语义变更）按"先改代码 → 再改 docs → 再改 skill"顺序落两边。
- skill 形态遵循 halo-plugin-dev 范式：SKILL.md 是路由器（概述 + Quick Start + 工作流 + 触发式索引），密集事实下沉 `references/`；接口签名类文件（`sdk-reference.md`）按"指路而非复制"原则把语义下沉到主题文件，自身只留签名与离线快照声明。
- 本次落地：references 更名 8 个文件并修正互链、SKILL.md 重写（221 → 121 行）、changelog 增"破坏性 / 高影响变更速查表"与 v3.9.0 条目、`sdk-reference.md` 剪枝。插件 API 面无任何变更。

## 备选方案（Alternatives）

- **docs 唯一权威 + skill 薄指针**：否决——用户级副本跨项目时所有链接失效，AI 失去程序性知识来源，违背 skill 的存在意义。
- **skill 唯一权威 + docs 派生**：否决——开源文档有自身受众与教程结构（README + reference），从 AI 副本派生方向拧巴；skill 的触发式索引、离线快照形态也不适合人读。
- **维持"逐字一致"承诺并加同步校验**：否决——两边受众与形态不同，逐字一致意味着两边都按同一形态写，要么 docs 变成 AI 索引（人难读），要么 skill 变成教程（AI 上下文浪费）；本次漂移正说明该承诺不可执行。
