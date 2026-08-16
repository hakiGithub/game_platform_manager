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
│   ├── styles/            # Night Ops token 副本 + Element 暗色重肤（ADR-0007）
│   ├── utils/runtime.ts    # detectMode
│   ├── utils/pluginSDK.ts  # Wujie 通信封装
│   └── utils/wujiePopperFix.ts  # Wujie popper 定位修正（§9）
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

## 8. Night Operations 主题与 token 隔离（ADR-0007，v3.5.0）

主应用为暗色 "Night Operations" 设计语言，插件前端必须对齐，否则嵌入时白底刺眼、视觉割裂。

- **token 复制而非共享**：Wujie shadow DOM 不继承宿主 CSS 变量，构建期跨目录 import 又破坏插件独立打包——把主应用 `frontend/src/styles/variables.scss` 完整复制到 `plugin-{gameCode}/frontend/src/styles/variables.scss`，两份文件头部互相注明同步关系；小工具（`utils/terminalTheme.js` 等）同样复制。
- **暗色单主题**：`main.ts` 直接 `document.documentElement.classList.add('dark')`，不实现 themeInfo 明暗切换（Wujie 是唯一生产运行模式，宿主永远暗色）。
- **页面模式**：统一页头（mono kicker `GAMECODE COMMAND / XXX` + 标题 + 描述 + 操作区）、状态用 `status-dot` + `--platform-status-*`、卡片 surface-1 + 1px line 边框 + 6px 圆角无阴影、卡片内不再出现裸十六进制浅色。
- **⚠️ sass 私有成员陷阱**：主应用 variables.scss 的变量名以 `$--` 开头（如 `$--color-primary`）。主应用用旧版 `@import` 能读；插件若用 `@use ... as *`（vite additionalData 注入），**前导 `-` 的成员是私有的、不可见**，会报 Undefined variable。复制到插件时须去掉 `$--` 前缀（`$--color-primary` → `$color-primary`）。
- **⚠️ Element 暗色变量特异性**：Element Plus 的 dark css-vars 定义在 `html.dark` 下，特异性 (0,1,1) 高于裸 `:root` (0,1,0)。插件 index.scss 末尾覆盖 `--el-*` 时选择器要写 `:root, html.dark { ... !important }`，否则弹层 overlay 等仍取 Element 原生暗灰。

## 9. Wujie 下 popper 定位漂移（v3.5.0）

**现象**：Element Plus 下拉框（el-select 等）弹层在 Wujie 嵌入下漂移（叠进下方表格 / 跑到视口左上角），dev 模式正常。

**根因**：popper 的 flip/preventOverflow 修饰器基于 iframe 沙箱 window 尺寸判断空间（恒不可用 → 误翻转为 right/top），坐标又按错误的 offsetParent（跨 shadow 解析到宿主侧定位祖先）计算。

**正确修法（运行时几何修正）**：见 `plugin-l4d2/frontend/src/utils/wujiePopperFix.ts`——App.vue 挂载时安装；监听 click/scroll/resize（capture），双 rAF 后把可见 `.el-select__popper` 改 `position: fixed` 并贴合到 `aria-expanded="true"` 输入框所属 `.el-select` rect 正下方。

**❌ 不要用的方案**（均在 Wujie 下破坏弹层开关或定位）：
- `popper-options` 改 `strategy: 'fixed'` → 弹层无法打开（沙箱内 update 抛异常）
- `popper-options` 禁用 flip/preventOverflow → placement 正确但坐标仍错位
- `:teleported="false"` → 被 el-card overflow 裁剪 + 点击事件经 shadow 重定向后开关失灵
