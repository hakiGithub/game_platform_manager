# ADR-0001: 插件菜单归属与 `getMenus()` 扩展点

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-02 |
| 决策者 | User (grilling session) |
| 相关 spec | [2026-08-02-plugin-menu-decoupling-design.md](../superpowers/specs/2026-08-02-plugin-menu-decoupling-design.md) |
| Supersedes | 无 |

## 背景（Context）

`PluginFrameworkServiceImpl#buildDefaultMenus` ([源码](../../backend/core/src/main/java/com/gameplatform/plugin/service/impl/PluginFrameworkServiceImpl.java#L369-L425)) 在主应用 core 模块内硬编码 L4D2 插件的菜单清单：

- 直接写出 `/rcon`、`/maps`、`/map-center`、`/player-stats`、`/server-info`、`/server-config`、`/restart`、`/backup`、`/logs` 等 L4D2 专属路由
- 读取 `manifest.features.rcon / mapManagement / playerManagement` 三个插件私有 feature flag 来 gate 菜单 —— 主应用反向耦合了 L4D2 的能力语义
- 与 [L4D2Extension#getManifest](../../backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java#L75-L97) 中的 `frontend.menus` 重复声明：插件给了 17 项，主应用只放出 10 项，且互相不一致（插件有 `游玩时长 / 管理员 / 版本信息 / 插件管理 / 预设场景 / 下载管理`，主应用全丢）
- 插件注释自承："requireInstance 字段由 `PluginFrameworkServiceImpl.buildDefaultMenus()` 设置，此处仅作为元数据透传" —— 即插件自己的清单被主应用覆盖

这违反了 [AGENTS.md 约定](../../AGENTS.md)：插件模块禁止直接依赖 game-platform-core，主应用也不应反向耦合具体插件业务。

## 决策（Decision）

主应用 `PluginFrameworkServiceImpl` 不再硬编码任何插件菜单；菜单清单由插件通过扩展点方法显式返回，主应用仅做拼装、空校验与排序。

具体决策项（经 grilling session 收敛）：

1. **菜单声明入口**：在 `GameEnhancementExtension` 接口新增 `default List<PluginMenuDeclaration> getMenus()` 方法，返回强类型对象。`buildDefaultMenus` 整段删除。

2. **通用菜单归属**：全部菜单归插件。主应用不预置任何"通用菜单"（如仪表盘、备份、日志等）。L4D2 的 17 项菜单全部由 `L4D2Extension.getMenus()` 返回。未来其他游戏插件自行决定是否需要 backup/restart 等页面。

3. **`features` map 处理**：`manifest.features` 字段彻底删除。`rcon / mapManagement / playerManagement` 三个 flag 不再使用。插件在 `getMenus()` 内部根据自身能力决定返回哪些菜单。

4. **`requireInstance` 默认值**：`PluginMenuDeclaration.requireInstance` 默认 `true`（绝大多数页面依赖实例）。插件为纯资源页（如 `/map-center`）显式调用 setter 设为 `false`。

5. **菜单 path 唯一性**：同插件内 `PluginMenuDeclaration.path` 必须唯一。`PluginFrameworkServiceImpl` 拼装时检测重复，重复则抛 `IllegalStateException` 拒绝缓存。不同插件间不检查（菜单已按 pluginId/gameCode 隔离）。

6. **缓存策略**：`manifestCache` 保留。`getMenus()` 在 `getManifestByPluginId` 拼装 manifest 时调用一次，结果随 manifest 一起缓存。缓存失效沿用现有 `startPlugin / stopPlugin / reloadPlugin / unloadPlugin` 时机。

7. **`capabilities` 字段填充**：`manifest.frontend.capabilities` 从 `features.keys()` 改为从 `getMenus()` 返回的菜单 path 集合推导：`capabilities = menus.stream().map(PluginMenuDeclaration::getPath).toList()`。

8. **Standalone 同步修复**：`plugin-l4d2-standalone` 模式同样从 `L4D2Extension.getMenus()` 读取菜单，保证 Wujie / Standalone / Vite 三种模式菜单一致。

9. **`loadManifestFromFile` 机制**：删除从 JAR 内读取静态 `manifest.json` 文件的双路径机制。manifest 仅从扩展点 `getManifest()` + `getMenus()` 构建，路径单一，调试简单。

10. **`PluginMenuDeclaration` 类型**：在 `backend/plugin` 模块 `com.gameplatform.plugin.extension` 包新建 `PluginMenuDeclaration` 类（Lombok `@Builder @Data`），字段：`title / path / icon / order / parent / requireInstance`。与 `PluginManifestVO.MenuConfig` 字段一一对应但独立于 VO，避免 plugin 模块依赖 core 的 VO。

11. **测试覆盖**：框架侧新建 `PluginFrameworkServiceImplMenusTest`（Mock 扩展点返回固定菜单，验证拼装、排序、requireInstance 默认值、path 重复检测）；插件侧新建 `L4D2ExtensionMenusTest`（验证 17 项菜单完整性）。

12. **迁移路径**：一次性切换。一个 PR 完成 plugin 模块扩展点新增、L4D2Extension 实现、PluginFrameworkServiceImpl 切换、loadManifestFromFile 删除、features 删除、standalone 同步修复。避免中间状态菜单丢失。

## 后果（Consequences）

### 正面

- **主应用与插件解耦**：core 模块不再出现任何 L4D2 专属字符串或 feature flag。新增游戏插件无需修改 core 代码。
- **菜单单一来源**：插件作者在 `getMenus()` 一处声明菜单，主应用与 standalone 模式读取同一份数据，消除清单不一致。
- **类型安全**：`PluginMenuDeclaration` 强类型对象取代 `Map<String, Object>`，编译期捕获字段拼写错误。
- **L4D2 菜单完整暴露**：`游玩时长 / 管理员 / 版本信息 / 插件管理 / 预设场景 / 下载管理` 等 7 项此前被主应用吞掉的菜单重新可见。
- **`features` 双轨制消除**：能力描述不再分散在 `features` 与 `menus` 两处。

### 负面

- **每个插件需重复声明通用菜单**：仪表盘、备份、日志等页面若多插件都需要，各自 `getMenus()` 中重复声明。缓解：可在 plugin SDK 提供 `StandardMenus` 常量类供引用，但本 ADR 不强制。
- **manifest.json 静态文件机制移除**：依赖静态 manifest.json 的部署场景（目前无）将不再支持。当前所有插件走动态构建，无实际影响。
- **缓存期间菜单不更新**：插件 `getMenus()` 修改后需 reload 插件才生效。与现有开发流程一致，但需在插件开发 SKILL 中明确。

### 中性

- `PluginManifestVO.MenuConfig` 与 `PluginMenuDeclaration` 字段一一对应，框架侧在 `buildManifestFromExtension` 中做对象映射，无序列化开销差异。

## 备选方案（Alternatives Considered）

### A. 继续用 `manifest.frontend.menus` Map 结构

保留 `getManifest()` 返回的 `Map<String, Object>`，主应用仅改为读取 `manifest.frontend.menus` 而不再覆盖。

- 优点：改动最小，L4D2Extension 已有 17 项 menus 数据
- 否决理由：`Map<String, Object>` 弱类型，`requireInstance` 拼写错误不会编译报错；后续扩展字段（如 `i18nKey / badge`）成本高；与"扩展点应提供强类型 API"的工程约定不符

### B. 静态 `manifest.json` 文件

插件 JAR 内 `ui/manifest.json` 静态声明菜单，主应用 `loadManifestFromFile()` 已支持。

- 优点：完全声明式，无需 Java 代码
- 否决理由：无法根据运行时条件动态裁剪菜单；与现有 `getManifest()` 动态构建机制冲突；当前 L4D2 插件未提供 `manifest.json` 文件，实际总是走扩展点路径

### C. 主应用提供 standard 菜单池，插件 opt-in

主应用定义 `StandardMenus.BACKUP / RESTART / LOGS` 等常量，插件 `getMenus()` 中引用这些常量 opt-in。

- 优点：减少插件重复声明
- 否决理由：定义"哪些算通用"本身又是主应用侧的预设偏见；若插件想自定义 backup 页面路径又会冲突；当前仅 L4D2 一个插件，无证据支撑"通用菜单"抽象

### D. 主应用提供 default 菜单，插件可 override

主应用 `buildDefaultMenus` 保留通用菜单作为默认值，插件 `getMenus()` 返回的菜单按 path 覆盖默认值。

- 优点：兼容现有体验
- 否决理由：override 语义复杂（按 path 替换还是合并子项？）；主应用仍保留一份菜单清单，没有彻底解决"主应用写死插件菜单"问题

### E. fallback 渐进迁移

保留 `buildDefaultMenus` 作为 fallback，仅当 `extension.getMenus()` 返回空列表时才走 `buildDefaultMenus`。

- 优点：可逐步迁移各插件
- 否决理由：当前仅 L4D2 一个插件，无渐进迁移收益；fallback 路径会永久残留变成死代码

## 开放问题（Open Questions）

以下问题本 ADR 不决策，留待未来需求驱动时新建 ADR：

1. **图标资源化**：当前 `icon` 字段为 Element Plus 图标名（如 `"Monitor"`）。若未来插件需自带 SVG 图标，需扩展 `PluginMenuDeclaration.iconType` 字段区分"组件名"与"资源路径"。
2. **国际化**：`title` 当前为中文字符串。若需 i18n，应将 `title` 改为 `titleKey`，由前端按当前语言查表。
3. **菜单权限**：当前所有菜单对所有用户可见。若需角色权限控制，应新增 `requiredPermission` 字段。
4. **`StandardMenus` 常量类**：本 ADR 决策项 12 提及但不强制。当第二个游戏插件出现且菜单高度重叠时再评估。

## 引用

- [PluginFrameworkServiceImpl#buildDefaultMenus](../../backend/core/src/main/java/com/gameplatform/plugin/service/impl/PluginFrameworkServiceImpl.java#L369-L425)（被删除）
- [L4D2Extension#getManifest](../../backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2Extension.java#L43-L100)（features 与 frontend.menus 字段被删除，菜单迁移到 getMenus()）
- [GameEnhancementExtension](../../backend/plugin/src/main/java/com/gameplatform/plugin/extension/GameEnhancementExtension.java)（新增 getMenus() default 方法）
- [AGENTS.md 关键工程约定](../../AGENTS.md)：plugin-l4d2 模块禁止直接依赖 game-platform-core
