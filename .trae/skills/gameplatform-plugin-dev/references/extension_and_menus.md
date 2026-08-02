# Extension 实现与菜单机制

> 对齐版本：v3.1.0（ADR-0001）｜ 权威源：`backend/plugin/` 源码

`GameEnhancementExtension` 继承 PF4J `ExtensionPoint`，是插件后端的核心扩展点。每个插件应恰好提供一个 `@Extension` 标注的实现。

## 1. 元数据（必须实现）

```java
@Extension
public class MyGameExtension implements GameEnhancementExtension {
    @Override public String getGameCode()    { return "mygame"; }      // 全局唯一，小写英文+连字符
    @Override public String getGameName()    { return "我的游戏"; }
    @Override public String getVersion()     { return "1.0.0"; }       // 语义化版本
    @Override public String getDescription() { return "游戏描述"; }
}
```

## 2. 清单（getManifest）

`getManifest()` 返回的 Map 会被宿主合并进 `PluginManifestVO.extensions` 字段，用于向前端透传插件自描述元数据（API 端点、版本、说明等）。

> ⚠️ **ADR-0001 弃用**：`getManifest()` 中曾经用于菜单生成的 `features` 字段**已废弃**，宿主不再读取；菜单由 `getMenus()` 单独声明（见 §4）。新插件不要再写 `features` 字段，旧插件应迁移到 `getMenus()`。

```java
@Override
public Map<String, Object> getManifest() {
    Map<String, Object> manifest = new HashMap<>();
    manifest.put("gameCode", getGameCode());
    manifest.put("gameName", getGameName());
    manifest.put("version", getVersion());
    manifest.put("description", getDescription());

    // API 端点列表（前端可读取用于自动发现 / 文档展示）
    Map<String, String> apiEndpoints = new HashMap<>();
    apiEndpoints.put("status", "/api/plugin/mygame/rcon/status");
    apiEndpoints.put("maps",   "/api/plugin/mygame/vpk/maps");
    manifest.put("apiEndpoints", apiEndpoints);

    // ❌ 不再写 features 字段（ADR-0001 已废弃）
    // ✅ 菜单清单迁移到 getMenus()（见 §4）
    return manifest;
}
```

## 3. 配置字段（getConfigFields）

声明后框架在前端自动渲染配置表单：

```java
@Override
public List<PluginConfigField> getConfigFields() {
    return List.of(
        PluginConfigField.builder()
            .key("rcon_port").label("RCON 端口")
            .type(PluginConfigField.FieldType.NUMBER)
            .defaultValue("27015").required(true).build(),
        PluginConfigField.builder()
            .key("rcon_password").label("RCON 密码")
            .type(PluginConfigField.FieldType.PASSWORD)
            .required(true).build(),
        PluginConfigField.builder()
            .key("game_mode").label("游戏模式")
            .type(PluginConfigField.FieldType.SELECT)
            .options(List.of("coop", "versus", "survival"))
            .defaultValue("coop").build()
    );
}
```

## 4. 生命周期钩子（完整）

```java
@Override public void onLoad(PluginContext context) { /* 子容器就绪后初始化 */ }
@Override public void onUnload()                    { /* 卸载前清理 */ }

@Override public void onInstanceCreate(Long instanceId, Map<String, Object> config) { /* 实例创建 */ }
@Override public void onInstanceStart(Long instanceId)  { /* 实例启动前 */ }
@Override public void onInstanceStop(Long instanceId)   { /* 实例停止后 */ }
@Override public void onInstanceDelete(Long instanceId) { /* 实例删除 */ }

@Override public void onLoadError(PluginContext context, Throwable error) { /* 加载失败 */ }
```

> `PluginContext`（v3.0.0）仅持有插件元数据：`getPluginId()` / `getGameCode()` / `getGameName()` / `getVersion()` / `getCustomProperties()`。**数据持久化通过 `ExtensionClient` 完成，不再经 PluginContext。**

## 5. 前端资源与依赖

```java
@Override public String getIcon()           { return "assets/icon.png"; }   // 相对 ui/ 目录
@Override public String getFrontendEntry()  { return "index.html"; }
@Override public String getBasePackage()    { return "com.gameplatform.plugin.mygame"; }
@Override public List<String> getDependencies() { return List.of("common-lib"); } // 依赖的 gameCode
```

## 6. 菜单声明（getMenus + PluginMenuDeclaration）★ ADR-0001

插件通过 `getMenus()` 返回 `List<PluginMenuDeclaration>` 声明自己的菜单清单，宿主 `PluginFrameworkServiceImpl.buildMenusFromDeclarations()` 负责校验 path 唯一性、补全 `requireInstance` 默认值，并序列化为 `PluginManifestVO.MenuConfig` 列表。

> 历史上菜单由宿主 `buildDefaultMenus()` 硬编码（依据 `features` map），仅 L4D2 适用，新插件无法新增菜单。ADR-0001 将菜单归属权交还插件，宿主不再预置任何默认菜单，也不再读取 `features` 字段。

### 6.1 PluginMenuDeclaration 字段

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `title` | `String` | 是 | — | 菜单标题（前端展示文案） |
| `path` | `String` | 是 | — | 菜单路径（子应用前端路由，如 `/rcon`）；同插件内必须唯一 |
| `icon` | `String` | 否 | null | Element Plus 图标组件名（如 `Monitor`） |
| `order` | `Integer` | 否 | null | 排序值（升序） |
| `parent` | `String` | 否 | null | 父菜单 path（用于二级菜单分组） |
| `requireInstance` | `Boolean` | 否 | `true` | `true`=需选中实例才能进入页面；`false`=纯资源浏览页（如地图中心） |

### 6.2 实现示例

```java
@Override
public List<PluginMenuDeclaration> getMenus() {
    return List.of(
        // 通用菜单（依赖 instanceId）
        PluginMenuDeclaration.builder()
            .title("仪表盘").path("/dashboard").icon("Odometer").order(1).build(),
        PluginMenuDeclaration.builder()
            .title("地图管理").path("/maps").icon("Map").order(2).build(),
        // 纯资源浏览页（不依赖 instanceId）
        PluginMenuDeclaration.builder()
            .title("地图中心").path("/map-center").icon("MapLocation").order(3)
            .requireInstance(Boolean.FALSE).build(),
        PluginMenuDeclaration.builder()
            .title("RCON 控制台").path("/rcon").icon("Monitor").order(4).build()
    );
}
```

### 6.3 宿主校验规则（buildMenusFromDeclarations）

| 校验 | 失败行为 |
|---|---|
| `path` 为空或空白 | 抛 `IllegalStateException`（提示插件 id + 菜单 title） |
| 同插件内 `path` 重复 | 抛 `IllegalStateException`（提示插件 id + 重复 path） |
| `requireInstance` 为 `null` | 框架补全为 `Boolean.TRUE` |

> 宿主**不预置任何默认菜单**（不再有"仪表盘/系统监控/服务器信息"等固定菜单），插件需要显式声明完整菜单列表（参考 `plugin-l4d2` 的 17 项菜单声明）。

### 6.4 路径对齐强约束

子应用前端路由 path（`plugin-{gameCode}/frontend/src/router/index.ts`）必须与 `getMenus()` 声明的 path **完全一致**，否则点击菜单白屏。

```ts
// frontend/src/router/index.ts
const routes = [
  { path: '/dashboard',    component: () => import('@/pages/Dashboard.vue') },
  { path: '/map-center',   component: () => import('@/pages/MapCenter.vue') },
  { path: '/rcon',         component: () => import('@/pages/Rcon.vue') },
  // ... 必须与 getMenus() 声明的 path 一一对应
]
```

---

## 7. 插件清单契约（PluginManifestVO）★

`PluginManifestVO` 是宿主向前端暴露的插件清单，由 `PluginFrameworkServiceImpl` 根据扩展点构建（`buildManifestFromExtension`），也可由插件 `ui/manifest.json` 覆盖（见 §8.5）。

```java
public class PluginManifestVO {
    String pluginId, gameCode, gameName, version, description, icon;
    String frontendEntry;                       // Wujie 子应用入口 URL
    FrontendConfig frontend;                    // 前端配置（含菜单）
    ApiConfig api;                              // API 配置
    Map<String, Object> extensions;             // 扩展点 getManifest() 原始数据（features 已废弃）

    static class FrontendConfig { String entry; List<RouteConfig> routes; List<MenuConfig> menus; List<String> assets; }
    static class RouteConfig   { String path, component, name; Map<String,Object> meta; }
    static class MenuConfig    {
        String title, path, icon, parent; Integer order;
        Boolean requireInstance;   // true=需选中实例才能进入；false=纯资源浏览页
    }
    static class ApiConfig     { String basePath; List<ApiEndpoint> endpoints; }
    static class ApiEndpoint   { String path, method, description; }
}
```

> `MenuConfig.requireInstance` 控制前端是否弹出实例选择对话框：`true`（如 RCON、地图管理）需携带 instanceId；`false`（如地图中心）可直接访问。该字段值由插件在 `getMenus()` 的 `PluginMenuDeclaration.requireInstance` 声明，宿主仅做默认值补全（null→true）。

获取清单：`GET /api/pf4j/plugin/{gameCode}/manifest`。

---

## 8. 菜单与前端加载机制 ★（ADR-0001）

### 8.1 菜单归属权在插件（ADR-0001）

**菜单由插件声明**：插件通过 `GameEnhancementExtension.getMenus()` 返回 `List<PluginMenuDeclaration>`（见 §6），宿主 `PluginFrameworkServiceImpl.buildMenusFromDeclarations()` 仅做校验与序列化。

> **历史变更**：v3.1.0 之前菜单由宿主 `buildDefaultMenus()` 硬编码，依据是 `getManifest()` 返回的 `features` map（`rcon`/`mapManagement`/`playerManagement`），仅 L4D2 适用。ADR-0001 将菜单归属权交还插件，`features` 字段已废弃，宿主不再预置任何默认菜单，也不再读取 `getManifest()` 中的 `frontend.menus` 字段（如存在会被忽略）。

### 8.2 完整加载链路

1. 插件 `getMenus()` 返回 `List<PluginMenuDeclaration>`（含 title/path/icon/order/parent/requireInstance）
2. 宿主 `buildMenusFromDeclarations()` 校验 path 非空、同插件内唯一，补全 `requireInstance` 默认值，序列化为 `PluginManifestVO.MenuConfig` 列表
3. `GET /api/pf4j/plugin/{gameCode}/manifest` 返回 `PluginManifestVO`（含 `frontend.menus`、`frontendEntry`）
4. 主应用读取 `manifest.frontend.menus` 渲染侧边栏（按 `requireInstance` 决定是否弹实例选择框）
5. 用户点击菜单 → 主应用 `PluginTab` 计算子应用 URL → Wujie 加载子应用（hash 路由）→ props 下发 instanceId 等

> **强约束**：子应用前端路由 path（`plugin-{gameCode}/frontend/src/router/index.ts`）必须与 `getMenus()` 声明的 path 严格对齐，否则点击菜单白屏。

### 8.3 requireInstance 行为

| 值 | 含义 | 主应用前端行为 | 示例 |
|---|---|---|---|
| `true`（默认） | 需选中实例才能进入页面 | 弹出实例选择对话框，选中后 Wujie 加载子应用并下发 `instanceId` | RCON、地图管理、服务器配置 |
| `false` | 纯资源浏览页，无需实例 | 直接加载子应用，不弹实例选择框 | 地图中心 |

> 插件声明 `requireInstance=null` 时宿主补全为 `true`；推荐显式设置 `Boolean.FALSE` 表达意图（避免依赖默认值导致误判）。

### 8.4 capabilities 派生（ADR-0001）

主应用不再依赖 `features` 字段判断插件能力，而是从 `manifest.frontend.menus` 的 path 集合推导 capabilities（如 `manifest.frontend.menus` 含 `/rcon` 即视为该插件具备 RCON 能力）。插件无需关心此派生逻辑。

### 8.5 ui/manifest.json（可选覆盖）

当存在 `ui/manifest.json` 时，框架优先使用文件清单而非从 Extension 构建（字段结构同 `PluginManifestVO`）。一般无需手写，依赖 Extension 自动构建即可。**注意**：`ui/manifest.json` 覆盖会绕过 `getMenus()` 校验，仅用于离线/调试场景。
