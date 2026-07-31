# GamePlatform 插件框架架构文档

> 版本: 2.0.0 | 更新日期: 2026-08-01

---

## 1. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        前端 (Vue 3 + Wujie)                   │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │ 主应用UI  │  │ 插件Tab(Wujie)│  │  插件清单动态加载       │ │
│  └────┬─────┘  └──────┬───────┘  └───────────┬────────────┘ │
└───────┼───────────────┼──────────────────────┼──────────────┘
        │               │                      │
        ▼               ▼                      ▼
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot 主应用 (Core)                  │
│  ┌────────────┐  ┌─────────────────┐  ┌──────────────────┐  │
│  │ REST API   │  │ 插件框架控制器    │  │ 静态资源服务      │  │
│  │ /api/**    │  │ /pf4j/**        │  │ /pf4j/plugin/**  │  │
│  └────────────┘  └────────┬────────┘  └──────────────────┘  │
│                           │                                  │
│  ┌────────────────────────┼────────────────────────────────┐│
│  │          插件框架核心 (Plugin Framework)                  ││
│  │                        │                                 ││
│  │  ┌─────────────┐  ┌────┴──────────┐  ┌───────────────┐  ││
│  │  │PluginManager│  │SpringContext  │  │PluginUtils    │  ││
│  │  │(PF4J)       │  │Factory        │  │(工具类)        │  ││
│  │  └──────┬──────┘  └────┬──────────┘  └───────────────┘  ││
│  │         │              │                                  ││
│  │  ┌──────┴──────┐  ┌────┴──────────┐  ┌───────────────┐  ││
│  │  │PluginAuto   │  │PluginFramework│  │PluginLifecycle│  ││
│  │  │Loader       │  │ServiceImpl    │  │Hook           │  ││
│  │  └─────────────┘  └───────────────┘  └───────────────┘  ││
│  └──────────────────────────────────────────────────────────┘│
│                           │                                  │
│  ┌────────────────────────┼────────────────────────────────┐│
│  │                 插件 Spring 子容器                        ││
│  │         (AnnotationConfigApplicationContext)             ││
│  │  ┌──────────┐  ┌───────────┐  ┌──────────────────────┐  ││
│  │  │@RestCtrl │  │@Service   │  │ExtensionClient     │  ││
│  │  │(动态注册) │  │(组件扫描)  │  │(ExtensionModel 隔离校验)       │  ││
│  │  └──────────┘  └───────────┘  └──────────────────────┘  ││
│  └──────────────────────────────────────────────────────────┘│
│                           │                                  │
│                    ┌──────┴──────┐                           │
│                    │  SQLite DB  │                           │
│                    └─────────────┘                           │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 模块依赖

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────┐
│   api       │◄────│  plugin     │◄────│     core        │
│ (公共DTO)   │     │ (扩展点API) │     │  (框架实现)      │
└─────────────┘     └──────┬──────┘     └────────┬────────┘
                           │                      │
                    ┌──────┴──────┐ ┌────────────┴──┐
                    │ plugin-l4d2 │ │  plugin-N     │
                    │ (参考实现)   │ │  (其他插件)    │
                    └─────────────┘ └───────────────┘
```

| 模块 | artifactId | 职责 |
|------|-----------|------|
| api | `game-platform-api` | 公共 DTO、请求/响应对象 |
| plugin | `game-platform-plugin` | 扩展点接口、VO、常量、异常、工具接口 |
| core | `game-platform-core` | 框架实现：插件管理、Spring集成、控制器、生命周期 |
| plugin-l4d2 | `plugin-l4d2` | L4D2 参考插件实现 |

---

## 3. 核心组件

### 3.1 插件管理器 — GamePlatformPluginManager

继承 PF4J `DefaultPluginManager`，定制化点：

- **JarPluginLoader**：使用 `parentFirst=true` 类加载策略，确保插件能访问主应用类
- **辅助方法**：`getLoadedPluginIds()`, `isPluginLoaded()`, `getPluginState()`

### 3.2 Spring 子容器工厂 — PluginSpringContextFactory

负责为每个插件创建独立的 Spring 容器并注册控制器。

**核心流程**：

```
loadPluginSpringContext(wrapper, extension, properties)
  │
  ├─ 1. 创建 AnnotationConfigApplicationContext 子容器
  │     ├─ setParent(mainContext)       — 继承主容器 Bean
  │     ├─ setClassLoader(pluginLoader) — 使用插件类加载器
  │     └─ registerSingleton(extensionClient) — 注入扩展资源客户端
  ├─ 2. 扫描 @ExtensionModel 并创建物理表
  ├─ 3. childContext.scan(basePackage)  — 扫描插件组件
  ├─ 4. childContext.refresh()          — 刷新容器
  ├─ 5. 发现 @RestController，提取映射信息
  │     ├─ 路径冲突检测（与已注册路径比对）
  │     └─ 注册到主 RequestMappingHandlerMapping
  ├─ 6. 构建 PluginContext 并注册到 PluginContextHolder
  ├─ 7. 调用 extension.onLoad(context)
  └─ 8. 缓存 PluginContextInfo
```

**关键设计**：

- 控制器注册/注销逻辑统一提取到 `extractEndpointMappings()`，消除重复代码
- 路径冲突检测通过 `registeredPaths` Map 实现
- 卸载时先调用 `extension.onUnload()` 钩子，再关闭子容器
- 使用 `RequestMappingInfo` 列表精确注销映射（不再重新解析注解）

### 3.3 插件框架服务 — PluginFrameworkServiceImpl

提供插件管理的核心业务逻辑（13个接口方法）。

**v2.0 关键改进**：

| 改进点 | 之前 | 之后 |
|--------|------|------|
| 清单缓存 | `HashMap`（非线程安全） | `ConcurrentHashMap` |
| findPluginIdByExtension | 重复实现3处 | 统一使用 `PluginUtils` |
| 路径构建 | 硬编码字符串 | `PluginUtils.buildXxx()` |
| 卸载钩子 | 不调用 onUnload | 传递 extension 调用钩子 |
| 错误处理 | 仅日志 | 调用 `extension.onLoadError()` |

### 3.4 插件自动加载器 — PluginAutoLoader

实现 `ApplicationRunner`，应用启动时自动加载插件。

**两阶段加载**：

1. **run()** — PF4J 加载+启动插件（`loadPlugins()` + `startPlugins()`）
2. **onApplicationReady()** — 应用就绪后创建 Spring 子容器

> 两阶段分离确保 PF4J 扩展点在 Spring 子容器创建前已就绪。

### 3.5 生命周期钩子 — PluginLifecycleHook

监听 PF4J 状态变更，同步数据库并执行扩展点钩子。

**PF4J 状态流转**：

```
CREATED → RESOLVED → STARTED → STOPPED → DISABLED
                        ↑                   │
                        └───────────────────┘
                          (可重新启动)
```

| 状态 | 数据库操作 | Extension 钩子 |
|------|-----------|---------------|
| CREATED | 创建 plugin_info 记录 | — |
| RESOLVED | 更新 load_time | — |
| STARTED | 更新 status=1, start_time | onLoad() / onInstanceStart() |
| STOPPED | 更新 status=0 | onInstanceStop() / onUnload() |
| DISABLED | 更新 status=0 | — |

### 3.6 插件工具类 — PluginUtils

集中管理框架通用方法，消除代码重复：

| 方法 | 用途 |
|------|------|
| `findPluginIdByExtension()` | 扩展点→插件ID（带缓存） |
| `findPluginIdByGameCode()` | 游戏编码→插件ID（带缓存） |
| `invalidateCache()` | 清除缓存（卸载/重载时） |
| `loadPluginProperties()` | 加载 plugin.properties |
| `stripApiPrefix()` | 去除 /api 前缀 |
| `buildResourceUrlPrefix()` | 构建资源 URL 前缀 |
| `buildFrontendEntryUrl()` | 构建前端入口 URL |
| `buildApiBasePath()` | 构建 API 基础路径 |

---

## 4. 扩展点体系

### 4.1 GameEnhancementExtension 接口

```
GameEnhancementExtension (extends ExtensionPoint)
  │
  ├── 元数据（必须实现）
  │     ├── getGameCode()          — 全局唯一标识
  │     ├── getGameName()          — 显示名称
  │     ├── getVersion()           — 语义化版本
  │     └── getDescription()       — 描述
  │
  ├── 清单与配置
  │     ├── getManifest()          — 前端清单数据
  │     └── getConfigFields()      — 配置字段声明 [v2.0 新增]
  │
  ├── 生命周期钩子
  │     ├── onLoad(context)        — 加载后初始化 [v2.0 新增]
  │     ├── onUnload()             — 卸载前清理 [v2.0 新增]
  │     ├── onInstanceCreate()     — 实例创建
  │     ├── onInstanceStart()      — 实例启动
  │     ├── onInstanceStop()       — 实例停止
  │     ├── onInstanceDelete()     — 实例删除
  │     └── onLoadError()          — 加载失败处理 [v2.0 新增]
  │
  ├── 前端资源
  │     ├── getIcon()              — 图标路径
  │     └── getFrontendEntry()     — 入口文件
  │
  ├── Spring 与数据库
  │     ├── getBasePackage()       — 组件扫描包名
  │     └── getExtensionModels()   — 扩展资源模型类列表
  │
  └── 插件依赖
        └── getDependencies()      — 依赖的 gameCode 列表 [v2.0 新增]
```

### 4.2 ExtensionClient 接口

```
ExtensionClient
  │
  ├── 创建
  │     └── create(T resource)                  — 创建扩展资源
  │
  ├── 查询
  │     ├── get(Class<T>, name)                 — 按 name 获取单个资源
  │     ├── list(Class<T>)                      — 列表查询
  │     ├── list(Class<T>, ListOptions)         — 带过滤/排序/分页的列表
  │     └── listAll(Class<T>, ListOptions)      — 跨页全量列表
  │
  ├── 更新
  │     ├── update(T resource)                  — 更新（乐观锁校验 version）
  │     └── updateStatus(T resource, status)    — 仅更新 status 字段
  │
  ├── 删除
  │     └── delete(Class<T>, name)              — 按 name 删除
  │
  └── 元信息
        ├── getPluginId()                       — 当前 client 绑定的 pluginId
        └── getTableName(Class<T>)              — 获取资源对应的物理表名
```

### 4.3 PluginContext 接口（v2.0 新增）

```
PluginContext
  │
  ├── getPluginId()            — 插件ID
  ├── getGameCode()            — 游戏编码
  ├── getGameName()            — 游戏名称
  ├── getVersion()             — 插件版本
  └── getCustomProperties()    — 自定义属性
```

> **注意**：v3.0 起 `PluginContext` 不再携带数据访问能力。持久化通过插件 Spring 子容器注入的 `ExtensionClient` Bean 完成。

通过 `PluginContextHolder` 在运行时任意位置访问：

```java
PluginContextHolder.getContext(pluginId)
    .ifPresent(ctx -> {
        log.info("插件 {} 版本 {}", ctx.getPluginId(), ctx.getVersion());
    });
```

---

## 5. 异常体系

```
RuntimeException
  └── PluginException (pluginId, message)
        ├── PluginLoadException         — 加载失败
        ├── PluginDataAccessException   — 数据访问违规
        ├── PluginConfigException       — 配置错误
        └── PluginPathConflictException — 路径冲突
```

---

## 6. 常量体系

`PluginConstants` 集中管理所有路径、配置键名、默认值：

| 类别 | 常量 | 值 |
|------|------|-----|
| 路径前缀 | `PLUGIN_RESOURCE_URL_PREFIX` | `/api/pf4j/plugin` |
| 路径前缀 | `PLUGIN_API_BASE_TEMPLATE` | `/api/plugin/{gameCode}` |
| 配置键名 | `PROP_PLUGIN_ID` | `plugin.id` |
| 配置键名 | `PROP_BASE_PACKAGE` | `plugin.basePackage` |
| 默认值 | `DEFAULT_FRONTEND_ENTRY` | `index.html` |
| Bean名称 | `ExtensionClient` Bean | `extensionClient` |

---

## 7. 控制器与资源服务

### 7.1 路由分布

| 路径前缀 | 控制器 | 用途 |
|---------|--------|------|
| `/pf4j/plugins/**` | `PluginFrameworkController` | 插件管理 API |
| `/pf4j/plugin/{gameCode}/ui/**` | `PluginFrameworkController` | 插件静态资源 |
| `/pf4j/plugin/{gameCode}/manifest` | `PluginFrameworkController` | 获取清单 |
| `/plugins/{gameCode}/ui/**` | `PluginResourceController` | 资源服务（兼容路径） |
| `/plugin/{gameCode}/ui/**` | `PluginFrameworkController` | Wujie 子应用静态资源入口 |
| `/api/plugin/{gameCode}/**` | 插件自定义控制器 | 插件业务 API |

### 7.2 静态资源服务

- 资源从插件 JAR 的 `ui/` 目录加载
- 路径穿越防护：阻止 `..`, `:`, `//` 等危险路径
- 7 天缓存策略
- 自动 Content-Type 推断（18 种文件类型）

### 7.3 缓存策略

- `index.html` 返回 `Cache-Control: no-store`，避免浏览器缓存导致新版本不生效
- 带 hash 的 JS/CSS 资源保留 7 天缓存

---

## 8. 数据库架构

### 8.1 主应用表

| 表名 | 用途 |
|------|------|
| `plugin_info` | 插件信息记录（状态、版本、运行时状态等） |
| `game_instance` | 游戏实例信息 |
| `server_instance` | 服务器实例 |

### 8.2 扩展资源表

插件通过 `@ExtensionModel` 声明扩展资源，框架自动建表。物理表名根据 `Strategy` 生成：

| 策略 | 物理表名 | 隔离粒度 |
|------|---------|---------|
| `SHARED` | `extensions` | 逻辑隔离（按 group_name + kind 过滤） |
| `PLUGIN_ISOLATED` | `ext_{pluginId}` | 插件间物理隔离 |
| `MODEL_ISOLATED` | `ext_{pluginId}_{kind}` | 模型级物理隔离 |

示例：
- `ext_plugin-l4d2_AdminResource` — L4D2 管理员资源
- `ext_plugin-l4d2_SystemMetricResource` — L4D2 系统指标资源

### 8.3 统一宽表结构

所有扩展资源表共用以下结构（复合主键 `(name, group_name, kind)`）：

```sql
CREATE TABLE {表名} (
    name                 TEXT    NOT NULL,
    group_name           TEXT    NOT NULL,         -- 框架填充 = pluginId
    kind                 TEXT    NOT NULL,         -- 框架填充 = 类名或注解 kind
    version              INTEGER NOT NULL DEFAULT 1, -- 乐观锁
    metadata             TEXT,                     -- JSON: labels/annotations/timestamps
    spec                 TEXT,                     -- JSON: 业务数据
    status               TEXT,                     -- 高频过滤字段
    creation_timestamp   INTEGER,
    update_timestamp     INTEGER,
    PRIMARY KEY (name, group_name, kind)
);
```

---

## 9. 类加载策略

```
Bootstrap ClassLoader
  └── App ClassLoader (主应用)
        └── Plugin ClassLoader (parentFirst=true)
              └── 插件类
```

**parentFirst=true** 的含义：
- 插件类加载器先委托父加载器加载
- 确保插件能访问主应用的 Spring、日志等框架类
- 避免类转换异常（ClassCastException）

---

## 10. 插件生命周期

### 10.1 完整生命周期

```
应用启动
  │
  ├─ PluginAutoLoader.run()
  │    ├─ pluginManager.loadPlugins()     — PF4J 加载 JAR
  │    │     └─ 状态: CREATED → RESOLVED
  │    └─ pluginManager.startPlugins()    — PF4J 启动
  │          └─ 状态: RESOLVED → STARTED
  │
  └─ ApplicationReadyEvent
       └─ PluginAutoLoader.onApplicationReady()
            └─ 为每个扩展点:
                 ├─ PluginUtils.findPluginIdByExtension()
                 ├─ PluginUtils.loadPluginProperties()
                 └─ PluginSpringContextFactory.loadPluginSpringContext()
                      ├─ 创建 Spring 子容器
                      ├─ 注册 ExtensionClient
                      ├─ 扫描 @ExtensionModel 并自动建表
                      ├─ 扫描组件 + 刷新容器
                      ├─ 注册控制器（路径冲突检测）
                      ├─ 注册 PluginContext
                      └─ 调用 extension.onLoad()
```

### 10.2 热重载流程

```
reloadPlugin(pluginId)
  │
  ├─ stopPlugin()     — 停止插件
  ├─ unloadPlugin()   — 卸载插件
  │    ├─ extension.onUnload()
  │    ├─ 注销控制器映射
  │    ├─ 关闭 Spring 子容器
  │    ├─ 注销 PluginContext
  │    └─ 清除缓存
  ├─ loadPlugin()     — 重新加载
  │    ├─ PF4J 加载 JAR
  │    ├─ 创建 Spring 子容器
  │    └─ extension.onLoad()
  └─ startPlugin()    — 启动插件
```

---

## 11. 前端集成

### 11.1 Wujie 微前端

插件前端作为 Wujie 子应用嵌入主应用：

```
主应用 (Vue 3)
  └── Wujie 子应用容器
        └── iframe → /api/pf4j/plugin/{gameCode}/ui/index.html
```

### 11.2 插件清单获取

前端通过 API 获取插件清单：

```
GET /api/pf4j/plugin/{gameCode}/manifest
  → PluginManifestVO (JSON)
    ├── pluginId, gameCode, gameName, version
    ├── frontendEntry (Wujie 入口 URL)
    ├── frontend.routes (前端路由)
    ├── frontend.menus (菜单项)
    └── api.basePath, api.endpoints (API 信息)
```

---

## 12. v2.0 改进总结

| 改进领域 | 改进内容 | 影响 |
|---------|---------|------|
| **基础设施** | 新增 PluginConstants、异常体系、PluginContextHolder | 消除硬编码，统一异常处理 |
| **扩展点** | 新增 onLoad/onUnload/onLoadError 钩子、getConfigFields、getDependencies | 更丰富的插件生命周期控制 |
| **数据访问** | 新增事务、批量操作、RowMapper、Map 查询 | 插件数据处理能力大幅提升 |
| **代码质量** | 消除 findPluginIdByExtension 3处重复、控制器注册逻辑统一 | 可维护性显著改善 |
| **线程安全** | manifestCache 改用 ConcurrentHashMap | 修复并发隐患 |
| **路径冲突** | 新增路径冲突检测和异常 | 避免插件间路由冲突 |
| **文档** | 新增架构文档和开发规范 | 降低插件开发门槛 |
