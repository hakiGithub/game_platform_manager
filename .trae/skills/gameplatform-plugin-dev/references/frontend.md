# 前端插件开发

> 对齐版本：v3.3.0（ADR-0003）｜ 权威源：`backend/plugin/` + `backend/plugin-l4d2/frontend/` 源码

## 1. 前端两种运行模式

通过 `detectMode()` 区分：

| 模式 | 检测条件 | 路由 history |
|---|---|---|
| Wujie 插件模式 | `window.__POWERED_BY_WUJIE__` 或 `props.mode==='wujie'` | `createWebHashHistory()` |
| Vite 开发模式 | 其余 | `createWebHistory('/')` |

> `standalone` 模式已废弃（ADR-0003，v3.3.0）。

## 2. 三种形态

| 形态 | 后端 | 前端 | 说明 |
|---|---|---|---|
| 纯后端插件 | ✅ Extension+控制器 | ❌ | 无 `ui/` 目录，主应用不渲染前端入口 |
| 纯前端插件 | ❌ | ✅ | **当前无实现，预留**。理论上需后端壳提供 manifest，暂不支持 |
| 双端插件 | ✅ | ✅ | 通过 `getMenus()` → 宿主拼装菜单 → Wujie 加载子应用配对（见 `references/extension_and_menus.md`） |

## 3. 纯前端插件（预留）

当前平台无纯前端插件实现。若未来支持，需后端提供一个最小 `GameEnhancementExtension` 壳以产出 manifest，前端子应用独立部署。**本章暂留**，不杜撰未实现的流程。

## 4. 运行模式检测（detectMode）

子应用通过 `utils/runtime.ts` 的 `detectMode()` 区分模式：

```ts
export type RuntimeMode = 'wujie' | 'dev'
export function detectMode(props: Record<string, any> = {}): RuntimeMode {
  if (props.mode === 'wujie' || window.__POWERED_BY_WUJIE__) return 'wujie'
  return 'dev'
}
```

路由 history 按模式选择：

```ts
// router/index.ts
const isWujie = Boolean(window.__POWERED_BY_WUJIE__)
const history = isWujie ? createWebHashHistory()        // Wujie: hash 路由
              : createWebHistory('/')                    // dev: 根路径
```

主应用通过 Wujie `props.route` 可指定子应用初始路由。

> **运行模式约定**：
> - 前端必须使用 3000 端口运行，通过 proxy 转发 /api 到后端 8080

## 5. 前后端通信（Wujie）

- **初始化数据**：主应用通过 Wujie `props` 下发（如 instanceId、token）。
- **运行时事件**：通过 Wujie `bus` 广播（`window.$wujie.bus`）。
- 子应用内通过 `window.$wujie.props` 读取初始数据，`window.$wujie.bus.$on/$emit` 收发事件。

> 已切换为 Wujie 微前端架构，旧版 postMessage 通信仅作兼容。

## 6. 前端目录结构

```
plugin-{gameCode}/frontend/
├── src/
│   ├── api/           # API 封装
│   ├── pages/         # 页面（路径须与 getMenus() 声明的 path 对齐）
│   ├── router/index.ts
│   ├── stores/        # Pinia
│   ├── utils/runtime.ts    # detectMode
│   └── utils/pluginSDK.ts  # Wujie 通信封装
├── vite.config.ts
└── package.json
```

## 7. 前端约定

- 子应用在 Wujie 模式下统一通过 App.vue 包裹 MainLayout（不再在每个页面嵌套 MainLayout），MainLayout 通过 `v-if="!isWujie"` 控制侧边栏渲染
- PluginTab.vue 生成子应用 URL 时必须使用 hash 路由格式 `${entry}#${path}`（如 `/index.html#/maps`），子应用使用 `createWebHashHistory` 解析
- 前端组件中禁止同时使用 `<script setup>` 和 Options API，所有函数需统一放在 `<script setup>` 中
- Wujie 微前端环境下，Element Plus 下拉框/弹出层在主前端未设置全局 z-index 时会被遮挡，需在主前端 `styles/index.scss` 中为所有 popper 类组件统一设置 `z-index: 9999`
- 配置管理表单需设置 `label-width` 为 220px，label 元素使用 `line-height: 32px`、`white-space: normal`、`word-break: break-word` 确保长字段名完整显示且与 input 框垂直对齐
- 表格布局需设置 `table-layout="fixed"` 并固定列宽，确保操作列按钮文字完整显示
