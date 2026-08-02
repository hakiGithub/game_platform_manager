# Glossary（术语表）

> 本术语表收录 Game Platform Manager 项目中跨模块、跨文档使用的关键术语。
> 与 [ADR 索引](README.md) 配套维护。新增术语时按字母序插入，并标注首次引入的 ADR 编号。

## 术语

### C

- **capabilities**
  - **定义**：`PluginManifestVO.frontend.capabilities` 字段，向主应用声明插件提供的能力清单。
  - **演变**：ADR-0001 前，由 `manifest.features.keys()` 推导（如 `["rcon", "mapManagement", "playerManagement"]`）；ADR-0001 后，改为由 `getMenus()` 返回的菜单 path 集合推导（如 `["/dashboard", "/rcon", "/maps", ...]`）。
  - **用途**：前端 `pluginStore.capabilities` 计算属性；未来可用于插件市场过滤、实例列表能力图标。
  - **引入**：ADR-0001

### E

- **Extension Point（扩展点）**
  - **定义**：PF4J 框架中插件实现宿主能力的契约接口。本项目中特指 `GameEnhancementExtension`。
  - **相关**：`@Extension` 注解标记实现类；`PluginManager.getExtensions(Class)` 加载实例。
  - **引入**：项目初始

### F

- **features（已废弃）**
  - **定义**：`manifest.features` Map 字段，原用于声明插件能力旗标（`rcon / mapManagement / playerManagement` 等布尔值）。
  - **状态**：ADR-0001 废弃。`getMenus()` 取代其菜单 gate 职责；`capabilities` 字段改为从菜单 path 推导。
  - **引入**：项目初始；**废弃于**：ADR-0001

### G

- **`GameEnhancementExtension`**
  - **定义**：游戏增强扩展点接口，每个插件恰好提供一个 `@Extension` 实现类。
  - **方法**：`getGameCode() / getGameName() / getVersion() / getDescription() / getManifest() / getConfigFields() / onLoad() / onUnload() / onInstanceCreate() / onInstanceStart() / onInstanceStop() / onInstanceDelete() / onLoadError() / getIcon() / getFrontendEntry() / getBasePackage() / getDependencies()`。
  - **演变**：ADR-0001 新增 `getMenus()` default 方法。
  - **引入**：项目初始（v2.0.0 重构）

### M

- **manifest**
  - **定义**：插件清单，描述插件元数据、前端入口、菜单、API 等信息。序列化为 `PluginManifestVO` 返回给前端。
  - **构建路径**：ADR-0001 后仅从扩展点 `getManifest() + getMenus()` 动态构建；`loadManifestFromFile()` 静态文件路径已删除。
  - **缓存**：`PluginFrameworkServiceImpl.manifestCache` 按 pluginId 缓存拼装结果，插件 start/stop/reload/unload 时失效。
  - **引入**：项目初始

### P

- **`PluginFrameworkServiceImpl`**
  - **定义**：主应用 core 模块中实现 `PluginFrameworkService` 接口的服务类，负责插件生命周期、manifest 拼装、资源读取。
  - **演变**：ADR-0001 删除 `buildDefaultMenus` 方法，改为调用 `extension.getMenus()` 拼装菜单。
  - **引入**：项目初始

- **`PluginMenuDeclaration`**
  - **定义**：插件菜单声明对象，由插件通过 `GameEnhancementExtension.getMenus()` 返回。
  - **字段**：`title / path / icon / order / parent / requireInstance`。
  - **包路径**：`com.gameplatform.plugin.extension.PluginMenuDeclaration`（plugin 模块）。
  - **约束**：同插件内 `path` 唯一；`requireInstance` 默认 `true`。
  - **引入**：ADR-0001

### R

- **`requireInstance`**
  - **定义**：菜单项是否要求选中实例后才渲染子应用。
  - **取值**：`true`（默认）—— 必须携带 instanceId 才能进入页面，如 RCON、地图管理；`false` —— 纯资源浏览页，无需实例即可访问，如地图中心。
  - **前端消费**：`PluginTab.vue` 的 `currentMenuRequireInstance` 计算属性依据此字段决定是否弹出实例选择对话框。
  - **演变**：ADR-0001 前由 `buildDefaultMenus` 在主应用侧设置；ADR-0001 后改由插件在 `PluginMenuDeclaration` 中显式声明。
  - **引入**：项目初始（字段已存在）；**职责迁移于**：ADR-0001

## 缩写

| 缩写 | 全称 | 含义 |
|------|------|------|
| ADR | Architecture Decision Record | 架构决策记录，本目录下每条文档记录一项长期影响的技术决策 |
| PF4J | Plugin Framework for Java | Java 插件框架，本项目使用 3.10.0 版本 |
| VO | Value Object | 视图对象，用于接口序列化 |
| SDK | Software Development Kit | 软件开发包，本项目指 `backend/plugin` 模块提供的扩展点与服务接口集合 |

## 相关文档

- [ADR 索引](README.md)
- [ADR-0001: 插件菜单归属与 getMenus() 扩展点](0001-plugin-menu-ownership.md)
- [插件开发指南](../../.trae/skills/gameplatform-plugin-dev/SKILL.md)
- [架构文档](../../architecture/ARCHITECTURE.md)
