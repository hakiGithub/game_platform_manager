# 06 - Wujie 微前端集成测试

> 主应用 ↔ 插件子应用 通信、菜单、路由集成测试

---

## 1. 集成架构回顾

```
┌─────────────────────────────────────────────────────┐
│  主应用 (frontend/)  http://localhost:3000          │
│  ┌───────────────┐  ┌────────────────────────────┐ │
│  │  Sidebar      │  │  PluginTab / Container     │ │
│  │  - 游戏管理    │  │  ┌──────────────────────┐ │ │
│  │  - 求生之路2   │→ │  │  Wujie Vue           │ │ │
│  │    - 仪表盘    │  │  │  (iframe sandbox)    │ │ │
│  │    - 地图管理  │  │  │  ┌────────────────┐  │ │ │
│  │    - RCON     │  │  │  │ 子应用         │  │ │ │
│  │  - 系统设置    │  │  │  │ plugin-l4d2    │  │ │ │
│  │               │  │  │  │ /frontend      │  │ │ │
│  └───────────────┘  │  │  └────────────────┘  │ │ │
│                     │  └──────────────────────┘ │ │
│                     └────────────────────────────┘
└─────────────────────────────────────────────────────┘
                              ↕ HTTP
┌─────────────────────────────────────────────────────┐
│  后端 (backend/)  http://localhost:8080             │
│  /api/pf4j/plugin/l4d2/ui/**  → JAR 资源            │
│  /api/plugin/l4d2/**          → 插件 REST API       │
└─────────────────────────────────────────────────────┘
```

---

## 2. 集成测试用例

### 2.1 资源加载

| ID | 用例 | 验证点 | 状态 |
|----|------|--------|------|
| WU-001 | 子应用 index.html 加载 | `/api/pf4j/plugin/l4d2/ui/index.html` 返回 200，Content-Type: text/html | ✅ |
| WU-002 | JS 资源加载 | `index-CSL1-AhF.js` 等 JS 资源可访问，无 403 | ✅ |
| WU-003 | CSS 资源加载 | `index-BljAI1pF.css` 等 CSS 资源可访问 | ✅ |
| WU-004 | 无 Token 访问 | SecurityConfig `/pf4j/plugin/*/ui/**` permitAll | ✅ |
| WU-005 | 字体/图片资源 | woff2/png/svg 等资源可访问 | ✅ |

### 2.2 菜单集成

| ID | 用例 | 验证点 | 状态 |
|----|------|--------|------|
| WU-010 | 插件菜单加载 | Sidebar `onMounted` 调用 `getPluginList` + `getPluginManifest` | ✅ |
| WU-011 | 一级菜单显示 | 插件作为一级菜单，标题为 `pluginName`（如"求生之路2"） | ✅ |
| WU-012 | 二级菜单显示 | 插件菜单项作为二级菜单（仪表盘、地图管理、RCON 等） | ✅ |
| WU-013 | 菜单路径对齐 | 菜单 path 与子应用路由一致（/dashboard、/maps、/rcon） | ✅ |
| WU-014 | 中文显示 | pluginName/description 正确显示中文，无乱码 | ✅ |
| WU-015 | 停止状态处理 | 插件停止后菜单消失或不显示子菜单 | 📋 |

### 2.3 实例选择

| ID | 用例 | 验证点 | 状态 |
|----|------|--------|------|
| WU-020 | 进入管理弹窗 | 点击"进入管理"弹出实例选择对话框 | ✅ |
| WU-021 | 按 gameCode 过滤 | 实例列表只显示对应游戏的实例 | ✅ |
| WU-022 | 选择实例跳转 | 选择后 `router.push('/plugin/{gameCode}')` 并携带 query | ✅ |
| WU-023 | query 参数传递 | instanceId/hostIp/deployPath 写入 URL query | ✅ |
| WU-024 | 空实例提示 | 无实例时显示空状态，引导创建 | 📋 |
| WU-025 | 取消选择 | 关闭对话框不跳转 | 📋 |

### 2.4 路由集成

| ID | 用例 | 验证点 | 状态 |
|----|------|--------|------|
| WU-030 | hash 路由传递 | PluginTab 生成 `${entry}#${path}` URL | ✅ |
| WU-031 | 菜单切换不 404 | 切换菜单时只请求 index.html，hash 不发到服务器 | ✅ |
| WU-032 | 子应用路由就绪 | 子应用 `createWebHashHistory` 读取 hash 并导航 | ✅ |
| WU-033 | 直接访问 /plugin/l4d2 | 默认重定向到 dashboard | ✅ |
| WU-034 | 刷新保持当前菜单 | URL 含 menuPath，刷新后仍定位到对应菜单 | ✅ |

### 2.5 Wujie 通信

| ID | 用例 | 验证点 | 状态 |
|----|------|--------|------|
| WU-040 | props 传递 | 主应用通过 `:props` 传递 instance/auth/theme | ✅ |
| WU-041 | instanceInfo 内容 | instanceId/instanceName/gameCode/hostId/hostIp/deployPath/ports | ✅ |
| WU-042 | authInfo 内容 | token/user.id/user.username/user.role/permissions | ✅ |
| WU-043 | themeInfo 内容 | isDark/theme | ✅ |
| WU-044 | READY 事件 | 子应用加载完成后发送 READY，主应用 loading=false | ✅ |
| WU-045 | 主题变化通知 | 主应用 theme 变化时通过 bus 通知子应用 | ✅ |
| WU-046 | gameCode 变化 | gameCode 变化时重新加载 manifest | ✅ |

### 2.6 子应用渲染

| ID | 用例 | 验证点 | 状态 |
|----|------|--------|------|
| WU-050 | MainLayout 隐藏 sidebar | Wujie 模式下 `v-if="!isWujie"` 不渲染 sidebar | ✅ |
| WU-051 | 无重复菜单 | 子应用页面内不再嵌套 MainLayout | ✅ |
| WU-052 | 单层菜单 | 只有主应用 Sidebar 一层菜单 | ✅ |
| WU-053 | 单独访问显示菜单 | 直接访问 index.html（非 Wujie）显示 sidebar | ✅ |
| WU-054 | 加载状态 | 显示 loading 图标，子应用就绪后隐藏 | ✅ |
| WU-055 | 错误重试 | 加载失败时显示错误页 + 重试按钮（最多 3 次） | ✅ |

### 2.7 跨域与安全

| ID | 用例 | 验证点 | 状态 |
|----|------|--------|------|
| WU-060 | context-path 剥离 | 主应用 /api 前缀剥离，子应用路径 /pf4j/plugin/l4d2/ui/** | ✅ |
| WU-061 | SecurityConfig permitAll | `/pf4j/plugin/*/ui/**` 不需要 Authorization | ✅ |
| WU-062 | API 调用带 Token | 子应用 API 请求通过 props 获取 token 并注入 header | ✅ |
| WU-063 | 子应用 sandbox | Wujie 默认 sandbox 隔离，子应用 window 独立 | ✅ |

---

## 3. 集成测试代码示例

### 3.1 主应用 Sidebar 动态菜单测试

```javascript
// frontend/src/tests/plugins/PluginMenu.test.js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import Sidebar from '@/layouts/Sidebar.vue'

vi.mock('@/api/plugin', () => ({
  getPluginList: vi.fn().mockResolvedValue({
    data: [{ pluginId: 'plugin-l4d2', running: true }]
  }),
  getPluginManifest: vi.fn().mockResolvedValue({
    data: {
      pluginId: 'plugin-l4d2',
      gameName: '求生之路2',
      frontend: {
        menus: [
          { title: '仪表盘', path: '/dashboard', icon: 'Monitor' },
          { title: '地图管理', path: '/maps', icon: 'MapLocation' }
        ]
      }
    }
  })
}))

describe('Sidebar 插件菜单', () => {
  it('应该加载并显示插件一级菜单', async () => {
    const wrapper = mount(Sidebar, {
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('求生之路2')
    expect(wrapper.text()).toContain('仪表盘')
    expect(wrapper.text()).toContain('地图管理')
  })
})
```

### 3.2 Wujie 通信测试

```javascript
// frontend/src/tests/plugins/pluginCommunication.test.js
import { describe, it, expect, vi } from 'vitest'
import { usePluginCommunication } from '@/plugins/communication/pluginCommunication'

describe('pluginCommunication', () => {
  it('应该在子应用 READY 后通知主应用', async () => {
    const onReady = vi.fn()
    const { simulateReady } = usePluginCommunication({
      name: 'l4d2-app',
      onReady
    })

    simulateReady({ version: '1.0.0' })

    expect(onReady).toHaveBeenCalledWith({ version: '1.0.0' })
  })

  it('应该传递 instance/auth/theme props', () => {
    const instanceInfo = { instanceId: 1, gameCode: 'l4d2' }
    const authInfo = { token: 'test-token' }
    const themeInfo = { isDark: false, theme: 'light' }

    const { props } = usePluginCommunication({
      name: 'l4d2-app',
      instanceInfo,
      authInfo,
      themeInfo
    })

    expect(props.value.instance).toEqual(instanceInfo)
    expect(props.value.auth).toEqual(authInfo)
    expect(props.value.theme).toEqual(themeInfo)
  })
})
```

### 3.3 E2E 验证（browser_use）

通过 TRAE 的 `browser_use` 子代理执行真实浏览器测试：

```
任务：验证插件子应用菜单加载
1. 打开 http://localhost:3000/
2. 登录（admin/admin123）
3. 检查左侧 Sidebar 是否显示"求生之路2"一级菜单
4. 展开菜单，检查是否包含：仪表盘、地图管理、RCON 控制台 等 10 个子菜单
5. 点击"仪表盘"
6. 检查是否弹出实例选择对话框
7. 选择一个实例
8. 验证 URL 变为 /plugin/l4d2/dashboard?instanceId=...
9. 验证页面加载（无 404、无 403）
10. 验证子应用内无重复的内部侧边栏
```

---

## 4. 常见问题排查

### 4.1 子应用 403 Forbidden

**原因**：SecurityConfig 中 AntPathRequestMatcher 路径不匹配

**排查**：
1. 检查实际请求 URL（如 `/api/pf4j/plugin/l4d2/ui/index.html`）
2. 检查 SecurityConfig 中的 matcher（应为 `/pf4j/plugin/*/ui/**`，注意单数 `plugin`）
3. Wujie 加载资源不带 Authorization header，必须 permitAll

### 4.2 菜单路径 404

**原因**：PluginTab 的 URL 拼接错误（如 `/index.html/maps` 而非 `/index.html#/maps`）

**排查**：
1. 检查 PluginTab.vue 的 `pluginUrl` 计算属性
2. 子应用使用 `createWebHashHistory`，路由应通过 hash 传递
3. 正确格式：`${entry}#${path}`

### 4.3 中文乱码

**原因**：Maven 编译时编码错误

**排查**：
1. 检查子 pom.xml 是否显式声明 maven-compiler-plugin + UTF-8
2. 父 pom `<pluginManagement>` 中的配置不会自动继承到子 pom
3. Windows 默认 file.encoding=GBK 会导致中文字符串常量损坏

### 4.4 三层菜单

**原因**：子应用页面嵌套了 MainLayout

**排查**：
1. 检查 App.vue 是否统一包裹 MainLayout
2. 检查各页面（Dashboard、Plugins、Admins、Rcon）是否移除了 `<MainLayout>` 包裹
3. MainLayout.vue 的 sidebar 应有 `v-if="!isWujie"` 条件渲染

### 4.5 子应用 props 未接收

**原因**：Wujie props 注入时机或访问方式错误

**排查**：
1. 子应用通过 `window.$wujie.props` 访问
2. 使用 `window.__POWERED_BY_WUJIE__` 判断是否在 Wujie 环境
3. 在 main.ts 中初始化时获取 props 并写入 store

---

*最后更新: 2026-07-20*
