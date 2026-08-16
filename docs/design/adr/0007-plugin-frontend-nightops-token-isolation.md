# ADR-0007: 插件前端 Night Operations token 隔离（复制而非共享）

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-16 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0002](0002-main-app-plugin-scope-isolation.md)（范围隔离）、[ADR-0003](0003-deprecate-plugin-l4d2-standalone.md)（废弃 standalone） |
| Supersedes | 无 |

## 背景（Context）

主应用前端在 redesign（commit `ea9278f` 及后续 sidebar 精修 `53d7193`/`c7b49c9`/`004c2fd`/`0271e10`）中切换到 **Night Operations** 暗色设计语言：`frontend/src/styles/variables.scss` 定义了 `--platform-*` 表面色阶（海军蓝 surface 0-3）、青色 `#27b5f3` 主色、状态语义色（`--platform-status-running/-stopped/-error/-deploying`）、8px 间距体系、6px 圆角卡片、mono kicker 标签等，并在 `index.scss` 对 Element Plus 做了整体暗色重肤。

`plugin-l4d2/frontend` 仍停留在旧的浅色 Element Plus 主题：自带一套浅色 token（`#409eff` 系）、页面内大量硬编码十六进制色值、`index.scss` 里的白色 `!important` popper 覆盖、以及 dev 模式自带的浅色侧边栏。Wujie 嵌入时，宿主壳是 Night Operations 暗色，插件内容区却是白色，视觉严重割裂。

## 决策（Decision）

1. **插件前端切换为暗色单主题**，对齐 Night Operations；删除 `themeInfo` 明暗切换逻辑（Wujie 是唯一生产运行模式，宿主永远暗色）。
2. **token 采用复制而非共享**：把主应用 `variables.scss` 的 Night Ops token 与 `index.scss` 的 Element 暗色覆盖复制进 `plugin-l4d2/frontend/src/styles/`，插件自管。两份文件头部互相注明同步关系。
3. **工具类同样复制**：`terminalTheme.js`（XTerm 配色）、`status-dot` 样式等小体量工具复制进插件，不跨目录引用主前端源码。

## 理由（Rationale）

- **Wujie 隔离**：微前端（shadow DOM）下宿主的 CSS 变量不会穿透进子应用，插件必须自带 token——运行时继承不可行。
- **构建期共享（跨目录 import `frontend/src/styles`）被否决**：会破坏插件独立构建/打包（插件前端随 plugin JAR 分发），且违反 ADR-0002 的范围隔离精神——插件样式自负。
- **运行时宿主注入被否决**：耦合宿主与插件版本，插件升级 token 需要同步升级宿主。
- **复制成本可控**：token 文件是纯声明式 SCSS/CSS 变量，变更频率低；头部同步注释 + 本 ADR 记录同步义务。

## 后果（Consequences）

- 正面：Wujie 嵌入与 dev 模式视觉统一；插件可独立构建分发。
- 负面：主应用 token 演进时需手动同步插件副本（同步点：`frontend/src/styles/variables.scss` ↔ `backend/plugin-l4d2/frontend/src/styles/variables.scss`，`terminalTheme.js` 同理）。
- 新插件应遵循同一模式：复制 Night Ops token 起步（可沉淀为脚手架，超出本 ADR 范围）。

## 验证

- dev 模式逐页检查无浅色残留；
- Wujie 嵌入模式（`/plugin/l4d2/ui/`）下检查与宿主壳的视觉衔接（popper/dropdown/dialog 暗色正常），并用浏览器自动化点击遍历主要页面。
