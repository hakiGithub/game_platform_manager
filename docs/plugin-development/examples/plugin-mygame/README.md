# plugin-mygame — 最小可运行双端插件示例

本目录是 GamePlatform 插件的**起步模板（starter template）**，自包含、不依赖 `plugin-l4d2` 源码即可理解，覆盖插件开发的核心能力：

- 后端：PF4J 入口（`MyGamePlugin`）、扩展点实现（`MyGameExtension` + `getMenus()` 菜单声明）、`@ExtensionModel` 扩展资源（`NoteResource` / `NoteSpec`）、REST 控制器 + `ExtensionClient` CRUD（含乐观锁）。
- 前端：Vue 3 + Vite 子应用，`detectMode()` 三模式检测、`router` path 与 `getMenus()` 严格对齐、API 封装（token 双源）、Pinia store。

## 目录结构

```
plugin-mygame/
├── pom.xml                     # 依赖宿主 game-platform-plugin / game-platform-api（provided scope）
├── src/main/
│   ├── resources/plugin.properties
│   └── java/.../               # MyGamePlugin / MyGameExtension / extension/ / controller/
└── frontend/                   # Vue 3 + Vite 子应用
    ├── package.json
    ├── vite.config.ts          # base: './'，outDir 输出到后端 JAR ui/，dev proxy /api
    └── src/                    # main.ts / App.vue / router / stores / api / utils / pages
```

## 作为新插件起点

1. 复制本目录为你的 `plugin-{gameCode}`。
2. 全局替换 `mygame` / `MyGame` / `mygame` 相关标识为你的游戏编码。
3. 后端 `mvn clean package`，将 JAR 放入主应用 `plugins/`。
4. 前端 `cd frontend && npm install && npm run build`（产物自动进入后端 `ui/`）。
5. 开发模式 `npm run dev`（端口 3100，proxy 转发 `/api` 到主应用 8080）。

## 配套文档

- 开发指南总入口：[../../README.md](../../README.md)
- 快速开始：[../../reference/getting-started.md](../../reference/getting-started.md)
- 菜单与扩展点：[../../reference/extension-and-menus.md](../../reference/extension-and-menus.md)
- 插件前端：[../../reference/frontend.md](../../reference/frontend.md)
