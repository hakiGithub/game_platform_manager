# 04 - 主应用测试用例

> 主应用前端（frontend/）测试用例规范与模板

---

## 1. 现有测试用例清单

### 1.1 单元测试 - API（frontend/src/tests/api/）

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `auth.test.js` | auth API | 登录、登出、获取用户信息 |
| `backup.test.js` | backup API | 创建/查询/还原/删除备份 |
| `game.test.js` | game API | 游戏列表、游戏元数据 |
| `host.test.js` | host API | 主机 CRUD、SSH 连接测试 |
| `instance.test.js` | instance API | 实例 CRUD、启停、状态查询 |
| `system.test.js` | system API | 系统信息、健康检查 |

### 1.2 单元测试 - Store（frontend/src/tests/stores/）

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `user.test.js` | useUserStore | 登录、登出、token 持久化、权限校验 |
| `host.test.js` | useHostStore | 主机列表加载、缓存、错误处理 |
| `instance.test.js` | useInstanceStore | 实例列表、状态轮询、部署任务跟踪 |
| `backup.test.js` | useBackupStore | 备份列表、进度跟踪 |

### 1.3 单元测试 - 工具（frontend/src/tests/utils/）

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `request.test.js` | axios request | 请求拦截、401 跳转、错误统一处理 |
| `websocket.test.js` | WebSocket | 连接、重连、消息分发 |
| `index.test.js` | 通用工具 | 格式化、防抖、深拷贝等 |

### 1.4 组件测试（frontend/src/tests/components/）

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `BackupForm.test.js` | BackupForm.vue | 表单校验、提交、取消 |
| `BackupProgress.test.js` | BackupProgress.vue | 进度展示、完成状态、失败提示 |
| `RestoreConfirm.test.js` | RestoreConfirm.vue | 确认对话框、回调触发 |

### 1.5 集成测试 - 视图（frontend/src/tests/views/）

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `login.test.js` | Login.vue | 登录表单、跳转、错误提示 |
| `host.test.js` | Host.vue | 主机列表、新增对话框、SSH 测试 |
| `instance.test.js` | Instance.vue | 实例列表、部署向导、详情页 |

### 1.6 集成测试 - 插件系统（frontend/src/tests/plugins/）

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `PluginContainer.test.js` | PluginContainer.vue | Wujie 加载、通信、错误重试 |
| `PluginMenu.test.js` | Sidebar 动态菜单 | 插件菜单加载、点击跳转 |
| `PluginTab.test.js` | PluginTab.vue | hash 路由、实例选择 |
| `pluginCommunication.test.js` | 通信层 | props 传递、事件总线、READY 握手 |
| `pluginSDK.test.js` | SDK | 主应用提供的 API |
| `pluginStore.test.js` | usePluginStore | 插件列表缓存、状态同步 |

---

## 2. 用例模板

### 2.1 单元测试模板（Store）

```javascript
// frontend/src/tests/stores/example.test.js
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useExampleStore } from '@/stores/example'

vi.mock('@/api/example', () => ({
  getList: vi.fn(),
  create: vi.fn()
}))

describe('useExampleStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该在 fetchList 后填充 list', async () => {
    const { getList } = await import('@/api/example')
    getList.mockResolvedValue({ data: { records: [{ id: 1 }] } })
    
    const store = useExampleStore()
    await store.fetchList()
    
    expect(store.list).toHaveLength(1)
    expect(store.list[0].id).toBe(1)
  })

  it('应该在 fetchList 失败时保持 list 不变', async () => {
    const { getList } = await import('@/api/example')
    getList.mockRejectedValue(new Error('network'))
    
    const store = useExampleStore()
    store.list = [{ id: 1 }]
    
    await expect(store.fetchList()).rejects.toThrow('network')
    expect(store.list).toHaveLength(1) // 保持原数据
  })
})
```

### 2.2 组件测试模板

```javascript
// frontend/src/tests/components/ExampleForm.test.js
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ExampleForm from '@/components/ExampleForm.vue'

vi.mock('@/api/example', () => ({
  create: vi.fn()
}))

describe('ExampleForm', () => {
  const mountComponent = (props = {}) => mount(ExampleForm, {
    global: {
      plugins: [ElementPlus],
      mocks: { $message: { error: vi.fn(), success: vi.fn() } }
    },
    props
  })

  it('应该渲染所有必填字段', () => {
    const wrapper = mountComponent()
    expect(wrapper.find('[data-testid=name-input]').exists()).toBe(true)
    expect(wrapper.find('[data-testid=submit-btn]').exists()).toBe(true)
  })

  it('应该在名称为空时禁用提交', async () => {
    const wrapper = mountComponent()
    const btn = wrapper.find('[data-testid=submit-btn]')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('应该在填写完整后提交并触发 success 事件', async () => {
    const { create } = await import('@/api/example')
    create.mockResolvedValue({ data: { id: 1 } })
    
    const wrapper = mountComponent()
    await wrapper.find('[data-testid=name-input]').setValue('test')
    await wrapper.find('[data-testid=submit-btn]').trigger('click')
    
    expect(create).toHaveBeenCalledWith({ name: 'test' })
    expect(wrapper.emitted('success')).toBeTruthy()
  })
})
```

### 2.3 视图集成测试模板

```javascript
// frontend/src/tests/views/ExamplePage.test.js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ExamplePage from '@/views/ExamplePage.vue'

vi.mock('@/api/example', () => ({
  getList: vi.fn()
}))

describe('ExamplePage', () => {
  let router, pinia

  beforeEach(() => {
    setActivePinia(createPinia())
    router = createRouter({
      history: createWebHistory(),
      routes: [{ path: '/', component: ExamplePage }]
    })
  })

  const mountPage = async () => {
    const wrapper = mount(ExamplePage, {
      global: {
        plugins: [ElementPlus, pinia, router]
      }
    })
    await router.isReady()
    await flushPromises()
    return wrapper
  }

  it('应该在加载时调用 getList 并渲染列表', async () => {
    const { getList } = await import('@/api/example')
    getList.mockResolvedValue({
      data: { records: [{ id: 1, name: 'test' }] }
    })

    const wrapper = await mountPage()

    expect(getList).toHaveBeenCalled()
    expect(wrapper.text()).toContain('test')
  })
})
```

---

## 3. 关键场景用例要求

### 3.1 登录流程

| 用例 | 验证点 |
|------|--------|
| 正确账号登录 | 跳转首页、token 存储、用户信息加载 |
| 错误密码 | 错误提示、不跳转、token 不存储 |
| Token 过期 | 401 拦截、清空 store、跳转登录 |
| 重复登录 | 已登录状态访问 /login 跳转首页 |

### 3.2 实例管理

| 用例 | 验证点 |
|------|--------|
| 列表加载 | 分页、筛选、排序 |
| 创建实例 | 部署向导流程、表单校验、端口检查 |
| 启停实例 | 状态更新、按钮禁用、loading |
| 详情页 | 静态数据先渲染、动态数据异步加载、15s 定时刷新 |
| 删除实例 | 确认弹窗、删除文件选项 |

### 3.3 主机管理

| 用例 | 验证点 |
|------|--------|
| SSH 连接测试 | 成功/失败提示 |
| Web 终端 | xterm 挂载、连接建立、输入输出 |
| 资源监控 | CPU/内存/磁盘图表 |

### 3.4 插件管理

| 用例 | 验证点 |
|------|--------|
| 插件列表 | pluginName 中文显示、状态正确 |
| 启停插件 | 状态变化、消息提示 |
| 进入管理 | 弹出实例选择对话框、选择后跳转 |
| 插件菜单 | 主应用 Sidebar 动态加载插件菜单 |

---

## 4. 数据-testid 规范

为关键交互元素添加 `data-testid` 以便 E2E 定位：

| 元素类型 | 命名规范 | 示例 |
|----------|----------|------|
| 输入框 | `{field}-input` | `username-input`、`password-input` |
| 按钮 | `{action}-btn` | `login-btn`、`submit-btn`、`cancel-btn` |
| 表格 | `{entity}-table` | `instance-table`、`host-table` |
| 对话框 | `{name}-dialog` | `create-instance-dialog` |
| 菜单项 | `{name}-menu` | `plugin-list-menu` |

---

*最后更新: 2026-07-20*
