# 路径常量速查与发布检查清单

> 对齐版本：v3.1.0（ADR-0001）｜ 权威源：`backend/plugin/` 源码

## 1. 路径常量速查

| 用途 | 路径 / 常量 | 来源 |
|---|---|---|
| 框架 API 前缀 | `/pf4j` | `PluginConstants.FRAMEWORK_API_PREFIX` |
| 插件静态资源 URL | `/api/pf4j/plugin/{gameCode}/ui/**` | `PLUGIN_RESOURCE_URL_PREFIX` |
| 插件 API 基础路径 | `/api/plugin/{gameCode}/**` | `PLUGIN_API_BASE_TEMPLATE` |
| 插件前端入口模板 | `/api/pf4j/plugin/{gameCode}/ui/{entry}` | `PLUGIN_FRONTEND_ENTRY_TEMPLATE` |
| 插件清单 API | `/api/pf4j/plugin/{gameCode}/manifest` | — |
| 插件框架管理 API | `/api/pf4j/plugins/**` | — |
| 默认前端入口 | `index.html` | `DEFAULT_FRONTEND_ENTRY` |
| 默认图标 | `assets/icon.png` | `DEFAULT_ICON` |
| 静态资源缓存 | 7 天（index.html 为 no-store） | `STATIC_RESOURCE_CACHE_DAYS` |

> `PluginConstants` 统一管理路径前缀与配置键名，避免硬编码。`plugin.properties` 键名：`plugin.id`/`plugin.class`/`plugin.version`/`plugin.gameCode`/`plugin.basePackage`。

### 安全配置约定

- 插件 UI 资源路径必须通过 SecurityConfig 配置允许访问，匹配模式为 `/pf4j/plugin/*/ui/**` 和 `/pf4j/plugins/*/ui/**`
- `PluginFrameworkController.getPluginResource` 对 `index.html` 返回 `Cache-Control: no-store`，其余带 hash 的 JS/CSS 保留 7 天缓存

---

## 2. 发布检查清单

### 2.1 检查清单

- [ ] `plugin.properties` 配置完整（id/class/version/gameCode/basePackage）
- [ ] `getGameCode()` 全局唯一，`getVersion()` 语义化版本
- [ ] 持久化资源类标注 `@ExtensionModel` 并继承 `AbstractExtension<T>`
- [ ] 控制器路径以 `/api/plugin/{gameCode}/` 开头
- [ ] `specFilter` 不直接拼 SQL
- [ ] 所有框架依赖 `provided` scope
- [ ] 前端入口 `ui/index.html` 存在（纯后端插件可无）
- [ ] 生命周期钩子正确实现（含 onInstanceCreate/Stop/Delete）
- [ ] 异常使用 `PluginException` / `ExtensionStoreException` 体系
- [ ] 任务 Handler 无状态，`@Component` 标注，Map 构造时缓存
- [ ] `execute` 循环检查 `isCancelled()`/`isTimeout()`
- [ ] `getDefaultTimeoutMs()` 返回合理超时；`maxRetryCount` 按副作用选取
- [ ] `getMenus()` 已声明完整菜单列表（双端插件必填）；同插件内 path 唯一
- [ ] 纯资源浏览页菜单显式设置 `requireInstance=Boolean.FALSE`（如地图中心）
- [ ] 前端路由 path 与 `getMenus()` 声明的 path 严格对齐
- [ ] `getManifest()` 不再写入 `features` 字段（ADR-0001 已废弃）

### 2.2 验收标准

开发者照本 SKILL 可完成一个最小插件并接入主应用跑通，至少包含：

1. 一个 `GameEnhancementExtension` 实现，含 `getMenus()` 菜单声明
2. 一个控制器（`/api/plugin/{gameCode}/` 前缀）
3. 一个 `@ExtensionModel` 资源 + `ExtensionClient` CRUD
4. 插件 JAR 放入 `plugins/` 后被主应用成功加载，`GET /api/pf4j/plugin/{gameCode}/manifest` 返回正确清单（含 `frontend.menus`）
5. （双端插件）前端子应用被 Wujie 加载，菜单可点击进入对应页面（路径对齐校验通过）
