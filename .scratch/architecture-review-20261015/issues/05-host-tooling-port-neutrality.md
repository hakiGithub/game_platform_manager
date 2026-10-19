# 05 — VPK 语义住在 core，工具容器端口不再中立

**Status:** resolved
**Type:** task
**来源:** 架构评审 2026-10-15（候选 5）· **与 ADR-0027 冲突（需先谈 ADR）**

## 现象

ADR-0026 设计 `HostToolingService.extractArchive(hostId, archive, dest, includePattern)` 时明确
「不开放裸容器执行权」，是一个**游戏中立**的语义端口。ADR-0027 随后在同一端口上加了 `analyzeVpk`，
于是 core 里住进了 L4D2 的战役/章节解释器：

| 产物 | 位置 |
|---|---|
| `VpkToolingAnalyzer` | `backend/core/.../clouddrive/`（core 的 `@Service`） |
| `vpk_analyze.py`（218 行 L4D2 解析器） | `backend/core/src/main/resources/tooling/` |
| `VpkAnalyzeResult` / `VpkInvalidException` | `backend/plugin/.../service/`（L4D2 类型进 SDK） |

CONTEXT.md 反复规定「语义层归插件」；`VpkToolingAnalyzer` 解释 missions/campaigns/chapters，正是语义层。

## 根因

ADR-0027 把 VPK 脚本放进 core 是当时的快路径。代价：
**第二款想做「在主机上跑一段平台脚本」的插件，必须复用 L4D2 的 analyzer**，
或再开一条 ADR 往端口上堆语义方法。

## 修复建议

端口暴露中立原语，VPK 解释权与脚本所有权退回插件：

```
HostToolingService.runToolScript(hostId, relativePath, scriptResource) → String  // 原始 stdout
```

core 只保留：instance→host 路由、工具容器调用、脚本分发、摘要。
插件自己拥有 `vpk_analyze.py` 与 `VpkAnalyzeResult` 的解析。

## 验收

- core 不再依赖游戏专属类型（`VpkAnalyzeResult` 移出 SDK）。
- 端口只暴露游戏中立原语；VPK 脚本随插件分发。
- L4D2 地图识别（ADR-0027）功能不变，回归通过。

## 关联

- **ADR-0027** 把 VPK 脚本嵌入 core，本票需重开该决策。
- 紧迫度取决于是否会出现第二个 VPK 类插件；若短期不会，可维持观察。

## Answer

- `HostToolingService` 只保留游戏中立能力：归档解压、文件摘要与 `runToolScript(instanceId, relativePath, scriptResource)`。
- VPK 剧本、`VpkAnalyzeResult` 与 `VpkInvalidException` 已迁入 L4D2 插件；插件通过 `plugin.tooling.scripts` 声明脚本，脚本资源按插件上下文解析。
- SDK 移除 VPK 专属 API；没有声明工具脚本的插件可正常加载。ADR-0027 与插件开发文档已同步。
- L4D2 地图识别功能保持不变，相关 Java/Python 回归通过。
- 提交：`95cc000`（合并提交 `2aedeed`）。
