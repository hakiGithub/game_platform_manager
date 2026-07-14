# plugin-l4d2-standalone 启动默认展示 UI 设计

> 日期：2026-07-15
> 状态：已批准
> 关联：`docs/superpowers/plans/2026-07-14-plugin-l4d2-dual-packaging.md`（双模式打包已实现）

---

## 1. 背景与目标

### 1.1 现状

`plugin-l4d2` 已完成双模式打包改造：

- **plugin-l4d2-core**：PF4J 插件 JAR，被主应用加载，前端资源位于 `src/main/resources/ui/`（Vite 构建产物）
- **plugin-l4d2-standalone**：Spring Boot fat JAR，独立运行，当前仅提供 REST API（端口 8081），**无任何前端资源**

前端源码位于 `backend/plugin-l4d2/frontend/`（Vue 3 + Vite + Element Plus），已实现 Wujie 微前端生命周期，支持双模式路由：

- Wujie 模式：`createWebHashHistory()`
- 独立模式：`createWebHistory('/plugin/l4d2/ui/')`

但前端只有 L4D2 业务页面（Dashboard/Maps/Plugins/Rcon/Monitor/Admins/ServerInfo/ServerConfig），无 host/instance 管理页面。

### 1.2 目标

让 `plugin-l4d2-standalone` 启动后默认展示 UI，且同一份前端代码既支持通过 Wujie 作为插件引入主应用，也支持通过 standalone 独立部署。

### 1.3 约束（用户确认）

| 决策点 | 选择 |
|--------|------|
| UI 页面范围 | 仅 L4D2 业务页，不新增 host/instance 完整管理页 |
| 实例定位方式 | 启动时显示实例选择页，用户从已有 instance 列表中选择 |
| 认证方式 | standalone 模式无需认证 |

---

## 2. 架构概述

### 2.1 核心思路

standalone 依赖 plugin-l4d2-core（compile scope），core JAR 中的 `ui/` 构建产物自然在 standalone classpath 中。standalone 通过 Spring 静态资源映射把 `classpath:/ui/` 暴露为 HTTP 可访问，前端一次构建、双模式运行。

### 2.2 三种运行模式

| 模式 | 触发条件 | API 前缀 | 认证 | 实例来源 |
|------|---------|---------|------|---------|
| Wujie 插件模式 | `window.__POWERED_BY_WUJIE__`=true | `/api/plugin/l4d2/*` | 主应用 JWT（SDK 注入） | 主应用 props 注入 |
| Standalone 部署模式 | 非 Wujie + 非 Vite dev | `/api/plugin/l4d2/*` + `/api/standalone/*` | 无需认证 | 启动时实例选择页 |
| Vite 开发模式 | `import.meta.env.DEV`=true | 代理到 8080/8081 | 无需认证 | 启动时实例选择页 |

### 2.3 关键发现

standalone 启动时扫描 `com.gameplatform.plugin.l4d2` 包，core 中的 L4D2 业务 Controller（RconController、AdminController 等）会被加载，它们的 API 路径 `/api/plugin/l4d2/*` 在 standalone 模式下同样有效。因此前端 L4D2 业务 API 层无需改动，只需新增 standalone 专用的 instance API 和实例选择页。

### 2.4 改动范围

- **后端（standalone 模块）**：新增 WebConfig（静态资源映射 + SPA fallback）+ SpaController（根路径重定向）
- **前端（plugin-l4d2/frontend）**：新增 standalone 模式检测 + 实例选择页 + standalone API 模块 + 路由/store 适配

---

## 3. UI 对打包的影响分析

### 3.1 core JAR 打包（plugin 模式）

- `ui/` 目录在 `plugin-l4d2-core/src/main/resources/ui/`，`mvn package` 后打入 core JAR
- 主应用通过 `PluginResourceController`（`/api/plugins/{gameCode}/ui/**`）从插件 JAR 读取 ui 资源
- **无影响**：打包流程不变，ui 资源已在 JAR 内

### 3.2 standalone fat JAR 打包

- standalone pom.xml 已 compile 依赖 plugin-l4d2-core，core JAR 被 spring-boot repackage 解压后，`ui/` 进入 fat JAR 的 `BOOT-INF/classes/ui/`
- Spring Boot 默认的 `ResourceHandlerRegistry` 扫描 `classpath:/static/`、`classpath:/public/`、`classpath:/resources/`、`classpath:/META-INF/resources/`，**但不含 `classpath:/ui/`**
- **需新增 WebConfig**：手动把 `classpath:/ui/` 加为静态资源位置

### 3.3 ui 资源更新时机

- 前端源码在 `plugin-l4d2/frontend/`，需手动 `npm run build` 输出到 core 的 `ui/`
- 如果开发者改了前端但没重新 build，core JAR 和 standalone JAR 都会用旧 ui
- **解法**：standalone 的打包流程中，ui 资源来自已构建好的 core JAR，不重复构建。开发者改前端后只需 `npm run build` + `mvn package` 即可，两个 JAR 同步更新

### 3.4 base 路径

- 当前 `vite.config.ts` 用 `base: './'`（相对路径），Wujie 模式下 OK
- standalone 模式下，index.html 在 `/ui/index.html`，相对路径 `./assets/*` 会解析为 `/ui/assets/*`，正确
- **无需改 vite.config.ts 的 base**

### 3.5 结论

打包流程不受影响。standalone 通过 WebConfig 显式映射 `classpath:/ui/` 即可读取 core JAR 中的前端资源，无需重复构建或复制。唯一要改的后端是新增静态资源映射配置。

---

## 4. 后端改动设计

新增文件位于 `plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/`。

### 4.1 StandaloneWebConfig.java

实现 `WebMvcConfigurer`，`addResourceHandlers` 把 `classpath:/ui/` 映射到 `/ui/**`。

由于 Spring Boot 默认不把 `classpath:/ui/` 当静态资源，需显式声明。

### 4.2 StandaloneSpaController.java

- `GET /` → 重定向到 `/ui/index.html`（启动后默认展示 UI）
- `GET /ui` → 重定向到 `/ui/index.html`
- SPA fallback：`GET /ui/**`（排除含文件扩展名的静态资源请求如 `/ui/assets/index-*.js`、`/ui/favicon.ico`）→ forward 到 `/ui/index.html`。实现方式：用 `PathPatternParser` 匹配 `/ui/**`，请求路径最后一段含 `.` 视为静态资源直接放行（交给 ResourceHandler），否则 forward 到 index.html
- 多级 SPA 路由（如 `/ui/instance-select`、`/ui/dashboard`）均能 fallback

### 4.3 不改动

- `StandaloneHostController`、`StandaloneInstanceController` 保持不变（API 路径 `/api/standalone/*`）
- core 中的 L4D2 业务 Controller 自动被扫描，API 路径 `/api/plugin/l4d2/*` 自动可用

### 4.4 验证点

- 启动后访问 `http://localhost:8081/` → 自动跳转 UI
- 访问 `http://localhost:8081/ui/assets/index-*.js` → 静态资源正常
- 刷新 `http://localhost:8081/ui/dashboard` → 返回 index.html（SPA 路由接管）

---

## 5. 前端改动设计

改动文件都在 `plugin-l4d2/frontend/src/`。

### 5.1 模式检测 — `utils/runtime.ts`（新增）

```typescript
export type RuntimeMode = 'wujie' | 'standalone' | 'dev';

export function detectMode(): RuntimeMode {
  if (window.__POWERED_BY_WUJIE__) return 'wujie';
  if (import.meta.env.DEV) return 'dev';
  return 'standalone';
}
```

集中模式判断，避免散落在各处检测 `window.__POWERED_BY_WUJIE__`。

### 5.2 实例选择页 — `pages/InstanceSelect.vue`（新增）

- 启动时（standalone/dev 模式）若未选实例，路由到此页
- 调 `GET /api/standalone/instances` 拉实例列表
- 展示实例卡片/表格（名称、主机IP、运行状态、游戏编码）
- 用户点击实例 → 存入 `pluginStore` → 跳转 `/dashboard`
- 空列表时提示"请先通过 API 添加主机和实例"

### 5.3 路由适配 — `router/index.ts`（修改）

- 新增 `/instance-select` 路由（standalone/dev 模式专用）
- 增加全局路由守卫：standalone/dev 模式下，未选择实例时重定向到 `/instance-select`（Wujie 模式跳过，实例由 props 注入）
- standalone 模式 base 路径调整：当前独立模式用 `createWebHistory('/plugin/l4d2/ui/')`，standalone 模式实际入口是 `/ui/index.html`，需改为 `createWebHistory('/ui/')`

### 5.4 API 适配 — `api/standalone.ts`（新增）+ `api/request.ts`（不改动）

- 新增 `standaloneApi` 模块，封装 `/api/standalone/instances` 和 `/api/standalone/hosts` 调用，供实例选择页使用
- `request.ts` 无需改动：Wujie 模式下走 SDK 代理，standalone/dev 模式下直接 fetch（同源，无 CORS 问题）

### 5.5 store 适配 — `stores/plugin.ts`（修改）

- `syncFromWujieProps()` 保持不变（Wujie 模式）
- 新增 `syncFromStandalone(instanceId)` 方法：standalone 模式下从实例选择页接收 instanceId，存入 store
- `instanceInfo` 的来源统一：Wujie 模式从 props，standalone 模式从选择页

### 5.6 main.ts 适配（修改）

- `bootstrap/mount/unmount` 生命周期保持不变（Wujie 模式）
- 独立挂载逻辑（非 Wujie）：当前已有 `#app` 挂载，无需改动
- 启动时根据 `detectMode()` 初始化：Wujie 模式调 `initSDK()`，standalone 模式跳过 SDK 初始化

### 5.7 不改动

8 个 L4D2 业务页面（Dashboard/Maps/Plugins/Rcon/Monitor/Admins/ServerInfo/ServerConfig）、`MainLayout.vue`、样式文件。

---

## 6. 数据流与错误处理

### 6.1 启动数据流（standalone 模式）

```
用户访问 http://localhost:8081/
  → StandaloneSpaController 重定向到 /ui/index.html
  → 浏览器加载 /ui/assets/index-*.js（来自 core JAR classpath:/ui/）
  → Vue 应用挂载，detectMode() = 'standalone'
  → 路由守卫检测未选实例 → 跳转 /instance-select
  → 实例选择页调 GET /api/standalone/instances
  → 用户选择实例 → pluginStore.syncFromStandalone(instanceId)
  → 跳转 /dashboard，L4D2 业务页用 instanceId 调 /api/plugin/l4d2/*
```

### 6.2 API 请求流（standalone 模式）

| 调用方 | 路径 | 后端处理 |
|--------|------|---------|
| 实例选择页 | `GET /api/standalone/instances` | StandaloneInstanceController |
| L4D2 业务页 | `GET /api/plugin/l4d2/rcon/status` 等 | core 的 L4D2 Controller（自动扫描） |

两类 API 同源，无需 CORS，无需 token。

### 6.3 错误处理

- **实例列表为空**：实例选择页显示空状态提示"请先通过 API 添加主机和实例"，附 API 示例（curl 命令）
- **实例选择后 API 失败**：L4D2 业务页已有错误提示逻辑（request.ts 统一拦截 `code !== 200` 抛错），保持不变
- **standalone 模式但 core 的 L4D2 Controller 启动失败**：Spring Boot 启动期会报错（已有 StandaloneExceptionHandler 兜底运行时异常）
- **前端资源缺失**（core JAR 无 ui/）：访问 `/ui/index.html` 返回 404，用户看到 Spring 默认 404 页。这是构建流程问题，不在运行时处理

### 6.4 测试策略

- **后端**：新增 `StandaloneWebConfigTest` 验证 `/ui/**` 静态资源映射生效、`/` 重定向到 `/ui/index.html`
- **前端**：新增 `runtime.test.ts` 验证三种模式检测；`InstanceSelect.test.ts` 验证空列表和实例选择流程
- **集成**：手动验证 `mvn package -pl plugin-l4d2-standalone` 后 `java -jar` 启动，浏览器访问根路径展示 UI

---

## 7. 验收清单

- [ ] `mvn package -pl plugin-l4d2-standalone` 生成 fat JAR，内含 ui 资源（`BOOT-INF/classes/ui/index.html`）
- [ ] `java -jar plugin-l4d2-standalone-1.0.0.jar` 启动后，浏览器访问 `http://localhost:8081/` 自动展示 UI
- [ ] 实例选择页正确拉取 `/api/standalone/instances` 并展示实例列表
- [ ] 选择实例后跳转 Dashboard，L4D2 业务 API 正常调用
- [ ] 刷新 `/ui/dashboard` 等子路径返回 index.html（SPA fallback 生效）
- [ ] Wujie 模式（主应用加载 plugin-l4d2-core）功能不受影响，无回归
- [ ] Vite 开发模式（`npm run dev`）实例选择页正常工作
- [ ] standalone 后端测试通过
- [ ] 前端单元测试通过

---

## 8. 涉及文件清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/.../config/StandaloneWebConfig.java` | 静态资源映射 |
| `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/.../config/StandaloneSpaController.java` | 根路径重定向 + SPA fallback |
| `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/.../config/StandaloneWebConfigTest.java` | 后端测试 |
| `backend/plugin-l4d2/frontend/src/utils/runtime.ts` | 模式检测 |
| `backend/plugin-l4d2/frontend/src/pages/InstanceSelect.vue` | 实例选择页 |
| `backend/plugin-l4d2/frontend/src/api/standalone.ts` | standalone API 模块 |
| `backend/plugin-l4d2/frontend/src/utils/runtime.test.ts` | 模式检测测试 |
| `backend/plugin-l4d2/frontend/src/pages/InstanceSelect.test.ts` | 实例选择页测试 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `backend/plugin-l4d2/frontend/src/router/index.ts` | 新增 `/instance-select` 路由 + 路由守卫 + base 路径 |
| `backend/plugin-l4d2/frontend/src/stores/plugin.ts` | 新增 `syncFromStandalone()` |
| `backend/plugin-l4d2/frontend/src/main.ts` | 根据 `detectMode()` 初始化 |

### 不改动文件

- `backend/plugin-l4d2/frontend/vite.config.ts`（base: './' 已满足）
- `backend/plugin-l4d2/frontend/src/api/request.ts`（同源 fetch 无需改）
- 8 个 L4D2 业务页面、`MainLayout.vue`、样式文件
- `StandaloneHostController`、`StandaloneInstanceController`
- core 模块所有文件
