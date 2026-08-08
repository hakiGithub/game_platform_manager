# Frontend - AI Agent 协作指南

> 游戏服务器管理平台前端开发指南

---

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.4.21 | 前端框架 |
| Vue Router | 4.3.0 | 路由管理 |
| Pinia | 2.1.7 | 状态管理 |
| Element Plus | 2.6.1 | UI组件库 |
| Axios | 1.6.8 | HTTP请求 |
| XTerm.js | 5.3.0 | Web终端 |
| Wujie | - | 微前端插件集成 |
| Vite | 5.2.0 | 构建工具 |
| Vitest | 1.4.0 | 测试框架 |
| Playwright | - | E2E / UI 自动化 |
| SCSS | 1.72.0 | CSS预处理器 |

---

## 项目结构

```
frontend/
├── public/                       # 静态资源
│   └── favicon.svg
├── src/
│   ├── api/                      # API接口封装
│   │   ├── auth.js                      # 认证接口
│   │   ├── host.js                      # 主机接口
│   │   ├── instance.js                  # 实例接口
│   │   ├── game.js                      # 游戏接口
│   │   ├── plugin.js                    # 插件接口
│   │   ├── backup.js                    # 备份接口
│   │   └── system.js                    # 系统接口
│   ├── components/               # 公共组件
│   │   ├── BackupForm.vue               # 备份表单
│   │   ├── BackupProgress.vue           # 备份进度
│   │   ├── DeployProgress.vue           # 部署进度
│   │   └── RestoreConfirm.vue           # 还原确认
│   ├── layouts/                  # 布局组件
│   │   ├── Header.vue                   # 顶部导航
│   │   ├── MainLayout.vue               # 主布局
│   │   └── Sidebar.vue                  # 侧边栏
│   ├── router/                   # 路由配置
│   │   └── index.js
│   ├── stores/                   # Pinia状态管理
│   │   ├── index.js                      # Store入口
│   │   ├── user.js                       # 用户状态
│   │   ├── host.js                       # 主机状态
│   │   ├── instance.js                   # 实例状态
│   │   ├── backup.js                     # 备份状态
│   │   └── app.js                        # 应用状态
│   ├── styles/                   # 样式文件
│   │   ├── index.scss                    # 全局样式
│   │   └── variables.scss                # 样式变量
│   ├── tests/                    # 测试文件
│   │   ├── setup.js                      # 测试配置
│   │   ├── api/                          # API测试
│   │   └── components/                   # 组件测试
│   ├── utils/                    # 工具函数
│   │   ├── request.js                    # Axios封装
│   │   └── websocket.js                  # WebSocket封装
│   ├── App.vue                   # 根组件
│   └── main.js                   # 入口文件
├── .env.development              # 开发环境变量
├── .env.production               # 生产环境变量
├── index.html                    # HTML模板
├── package.json                  # NPM配置
└── vite.config.js                # Vite配置
```

### 插件子应用

```
backend/plugin-l4d2/frontend/     # L4D2 插件前端
├── src/
│   ├── api/                      # 插件 API 封装
│   ├── components/               # 插件公共组件
│   ├── pages/                    # 插件页面
│   ├── router/                   # 插件路由
│   ├── utils/
│   │   └── pluginSDK.ts          # 插件 SDK，获取主应用注入上下文
│   ├── App.vue
│   └── main.ts
├── package.json
└── vite.config.ts                # outDir 指向 plugin-l4d2-core/src/main/resources/ui
```

---

## 运行模式

主前端为 SPA 应用，通过 Vite 构建，生产部署到后端 `core/src/main/resources/static/`。

| 模式 | 路由基座 | 说明 |
|------|----------|------|
| 生产模式 | `/` | 后端 Spring Boot 静态资源服务 |
| Vite 开发模式 | `/` | 本地开发，proxy 转发 `/api` 到后端 8080，前端固定使用 3000 端口 |

### 插件子应用运行模式

插件子应用（如 `plugin-l4d2/frontend`）支持两种运行模式，通过 `detectMode()` 区分：

| 模式 | 路由基座 | 说明 |
|------|----------|------|
| Wujie 插件模式 | `/plugin/l4d2/ui/` | 子应用被 Wujie 嵌入主应用，使用 `createWebHashHistory` |
| Vite 开发模式 | `/` | 本地开发，proxy 转发 `/api` 到后端 8080 |

> 注：`plugin-l4d2-standalone` 独立运行模式已废弃（ADR-0003）。

---

## 代码规范

### 文件命名

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 组件 | PascalCase | `BackupForm.vue` |
| 页面 | PascalCase + Page | `HostListPage.vue` |
| 工具 | camelCase | `request.js` |
| 样式 | kebab-case | `index.scss` |
| 测试 | 原文件名 + .test | `auth.test.js` |

### 组件规范

- 使用 `<script setup>` 语法
- 使用 Composition API
- Props 使用 `defineProps` 定义
- Emits 使用 `defineEmits` 定义
- 组件名使用多词组合避免冲突

### 代码风格

- 使用 Element Plus 组件库
- 使用 Pinia 进行状态管理
- API 请求统一放在 `api/` 目录
- 使用 SCSS 预处理器
- 遵循 ESLint + Prettier 规范

---

## 开发指南

### 1. 新增 API

```javascript
import request from '@/utils/request'

export function getInstanceList(params) {
  return request({
    url: '/instances',
    method: 'get',
    params
  })
}

export function getInstanceById(id) {
  return request({
    url: `/instances/${id}`,
    method: 'get'
  })
}

export function createInstance(data) {
  return request({
    url: '/instances',
    method: 'post',
    data
  })
}

export function updateInstance(id, data) {
  return request({
    url: `/instances/${id}`,
    method: 'put',
    data
  })
}

export function deleteInstance(id) {
  return request({
    url: `/instances/${id}`,
    method: 'delete'
  })
}

export function startInstance(id) {
  return request({
    url: `/instances/${id}/start`,
    method: 'post'
  })
}

export function stopInstance(id) {
  return request({
    url: `/instances/${id}/stop`,
    method: 'post'
  })
}
```

### 2. 新增 Store

```javascript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getInstanceList, getInstanceById } from '@/api/instance'

export const useInstanceStore = defineStore('instance', () => {
  const instances = ref([])
  const currentInstance = ref(null)
  const loading = ref(false)
  const total = ref(0)

  const runningCount = computed(() => 
    instances.value.filter(i => i.status === 1).length
  )

  async function fetchInstances(params) {
    loading.value = true
    try {
      const { data } = await getInstanceList(params)
      instances.value = data.records
      total.value = data.total
    } finally {
      loading.value = false
    }
  }

  async function fetchInstance(id) {
    loading.value = true
    try {
      const { data } = await getInstanceById(id)
      currentInstance.value = data
      return data
    } finally {
      loading.value = false
    }
  }

  function reset() {
    instances.value = []
    currentInstance.value = null
    total.value = 0
  }

  return {
    instances,
    currentInstance,
    loading,
    total,
    runningCount,
    fetchInstances,
    fetchInstance,
    reset
  }
})
```

### 3. 新增组件

```vue
<template>
  <div class="instance-card">
    <el-card shadow="hover" :body-style="{ padding: '16px' }">
      <div class="instance-header">
        <div class="instance-status">
          <el-tag :type="statusType" size="small">
            <el-icon><component :is="statusIcon" /></el-icon>
            {{ statusText }}
          </el-tag>
        </div>
        <div class="instance-name">{{ instance.name }}</div>
      </div>
      
      <div class="instance-info">
        <div class="info-item">
          <span class="label">游戏:</span>
          <span class="value">{{ instance.gameName }}</span>
        </div>
        <div class="info-item">
          <span class="label">主机:</span>
          <span class="value">{{ instance.hostName }}</span>
        </div>
        <div class="info-item">
          <span class="label">端口:</span>
          <span class="value">{{ instance.port }}</span>
        </div>
      </div>

      <div class="instance-actions">
        <el-button 
          v-if="instance.status === 0" 
          type="success" 
          size="small"
          @click="handleStart"
        >
          启动
        </el-button>
        <el-button 
          v-if="instance.status === 1" 
          type="warning" 
          size="small"
          @click="handleStop"
        >
          停止
        </el-button>
        <el-button 
          type="primary" 
          size="small"
          @click="handleDetail"
        >
          详情
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay, VideoPause, Warning } from '@element-plus/icons-vue'
import { startInstance, stopInstance } from '@/api/instance'

const props = defineProps({
  instance: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['refresh'])
const router = useRouter()

const statusType = computed(() => {
  switch (props.instance.status) {
    case 1: return 'success'
    case 2: return 'danger'
    default: return 'info'
  }
})

const statusText = computed(() => {
  switch (props.instance.status) {
    case 1: return '运行中'
    case 2: return '异常'
    default: return '已停止'
  }
})

const statusIcon = computed(() => {
  switch (props.instance.status) {
    case 1: return VideoPlay
    case 2: return Warning
    default: return VideoPause
  }
})

async function handleStart() {
  try {
    await startInstance(props.instance.id)
    ElMessage.success('实例启动成功')
    emit('refresh')
  } catch (error) {
    ElMessage.error(error.message || '启动失败')
  }
}

async function handleStop() {
  try {
    await ElMessageBox.confirm(
      '确定要停止该实例吗？',
      '警告',
      { type: 'warning' }
    )
    await stopInstance(props.instance.id)
    ElMessage.success('实例已停止')
    emit('refresh')
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error.message || '停止失败')
    }
  }
}

function handleDetail() {
  router.push(`/instances/${props.instance.id}`)
}
</script>

<style lang="scss" scoped>
.instance-card {
  .instance-header {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
    
    .instance-name {
      font-size: 16px;
      font-weight: 600;
    }
  }
  
  .instance-info {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
    margin-bottom: 16px;
    
    .info-item {
      .label {
        color: var(--el-text-color-secondary);
        margin-right: 4px;
      }
    }
  }
  
  .instance-actions {
    display: flex;
    gap: 8px;
  }
}
</style>
```

### 4. 新增页面

```vue
<template>
  <div class="instance-list-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>游戏实例</span>
          <el-button type="primary" @click="handleCreate">
            <el-icon><Plus /></el-icon>
            新建实例
          </el-button>
        </div>
      </template>

      <el-form :inline="true" :model="queryParams" class="search-form">
        <el-form-item label="实例名称">
          <el-input v-model="queryParams.name" placeholder="请输入" clearable />
        </el-form-item>
        <el-form-item label="主机">
          <el-select v-model="queryParams.hostId" placeholder="请选择" clearable>
            <el-option 
              v-for="host in hostStore.hosts" 
              :key="host.id" 
              :label="host.name" 
              :value="host.id" 
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="请选择" clearable>
            <el-option label="运行中" :value="1" />
            <el-option label="已停止" :value="0" />
            <el-option label="异常" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table 
        :data="instanceStore.instances" 
        v-loading="instanceStore.loading"
        stripe
      >
        <el-table-column prop="name" label="实例名称" min-width="150" />
        <el-table-column prop="gameName" label="游戏" width="120" />
        <el-table-column prop="hostName" label="主机" width="150" />
        <el-table-column label="地址" width="180">
          <template #default="{ row }">
            {{ row.hostIp }}:{{ row.port }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button 
              v-if="row.status === 0" 
              type="success" 
              size="small"
              @click="handleStart(row)"
            >
              启动
            </el-button>
            <el-button 
              v-if="row.status === 1" 
              type="warning" 
              size="small"
              @click="handleStop(row)"
            >
              停止
            </el-button>
            <el-button 
              type="primary" 
              size="small"
              link
              @click="handleDetail(row)"
            >
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="queryParams.current"
        v-model:page-size="queryParams.size"
        :total="instanceStore.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="fetchData"
        @current-change="fetchData"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useInstanceStore } from '@/stores/instance'
import { useHostStore } from '@/stores/host'
import { startInstance, stopInstance } from '@/api/instance'

const router = useRouter()
const instanceStore = useInstanceStore()
const hostStore = useHostStore()

const queryParams = ref({
  current: 1,
  size: 10,
  name: '',
  hostId: null,
  status: null
})

onMounted(() => {
  fetchData()
  hostStore.fetchHosts()
})

async function fetchData() {
  await instanceStore.fetchInstances(queryParams.value)
}

function handleSearch() {
  queryParams.value.current = 1
  fetchData()
}

function handleReset() {
  queryParams.value = {
    current: 1,
    size: 10,
    name: '',
    hostId: null,
    status: null
  }
  fetchData()
}

function handleCreate() {
  router.push('/instances/create')
}

function handleDetail(row) {
  router.push(`/instances/${row.id}`)
}

async function handleStart(row) {
  try {
    await startInstance(row.id)
    ElMessage.success('实例启动成功')
    fetchData()
  } catch (error) {
    ElMessage.error(error.message || '启动失败')
  }
}

async function handleStop(row) {
  try {
    await ElMessageBox.confirm(
      '确定要停止该实例吗？',
      '警告',
      { type: 'warning' }
    )
    await stopInstance(row.id)
    ElMessage.success('实例已停止')
    fetchData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error.message || '停止失败')
    }
  }
}

function getStatusType(status) {
  switch (status) {
    case 1: return 'success'
    case 2: return 'danger'
    default: return 'info'
  }
}

function getStatusText(status) {
  switch (status) {
    case 1: return '运行中'
    case 2: return '异常'
    default: return '已停止'
  }
}
</script>

<style lang="scss" scoped>
.instance-list-page {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  
  .search-form {
    margin-bottom: 16px;
  }
  
  .el-pagination {
    margin-top: 16px;
    justify-content: flex-end;
  }
}
</style>
```

---

## 工具函数

### Axios 封装

```javascript
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use(
  config => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    return config
  },
  error => Promise.reject(error)
)

request.interceptors.response.use(
  response => {
    const { code, message, data } = response.data
    if (code === 200) {
      return { data, message }
    }
    ElMessage.error(message || '请求失败')
    return Promise.reject(new Error(message))
  },
  error => {
    const { response } = error
    if (response) {
      switch (response.status) {
        case 401:
          const userStore = useUserStore()
          userStore.logout()
          router.push('/login')
          ElMessage.error('登录已过期，请重新登录')
          break
        case 403:
          ElMessage.error('没有权限访问')
          break
        case 404:
          ElMessage.error('资源不存在')
          break
        case 500:
          ElMessage.error('服务器错误')
          break
        default:
          ElMessage.error(response.data?.message || '请求失败')
      }
    } else {
      ElMessage.error('网络错误，请检查网络连接')
    }
    return Promise.reject(error)
  }
)

export default request
```

### WebSocket 封装

```javascript
class WebSocketClient {
  constructor(url, options = {}) {
    this.url = url
    this.options = options
    this.ws = null
    this.reconnectAttempts = 0
    this.maxReconnectAttempts = options.maxReconnectAttempts || 5
    this.reconnectInterval = options.reconnectInterval || 3000
    this.listeners = new Map()
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url)
      
      this.ws.onopen = () => {
        this.reconnectAttempts = 0
        this.emit('open')
        resolve()
      }
      
      this.ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          this.emit('message', data)
        } catch (e) {
          this.emit('message', event.data)
        }
      }
      
      this.ws.onerror = (error) => {
        this.emit('error', error)
        reject(error)
      }
      
      this.ws.onclose = () => {
        this.emit('close')
        this.handleReconnect()
      }
    })
  }

  send(data) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data))
    }
  }

  on(event, callback) {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, [])
    }
    this.listeners.get(event).push(callback)
  }

  off(event, callback) {
    if (this.listeners.has(event)) {
      const callbacks = this.listeners.get(event)
      const index = callbacks.indexOf(callback)
      if (index > -1) {
        callbacks.splice(index, 1)
      }
    }
  }

  emit(event, data) {
    if (this.listeners.has(event)) {
      this.listeners.get(event).forEach(callback => callback(data))
    }
  }

  handleReconnect() {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++
      setTimeout(() => {
        this.connect()
      }, this.reconnectInterval)
    }
  }

  close() {
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
  }
}

export default WebSocketClient
```

---

## 组件库使用

### Element Plus 按需导入

项目已配置自动导入，无需手动引入组件。

```javascript
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(ElementPlus, { locale: zhCn })
```

### 常用组件

| 组件 | 用途 |
|------|------|
| `el-table` | 数据表格 |
| `el-form` | 表单 |
| `el-dialog` | 弹窗 |
| `el-message` | 消息提示 |
| `el-notification` | 通知 |
| `el-loading` | 加载 |
| `el-tag` | 标签 |
| `el-button` | 按钮 |
| `el-input` | 输入框 |
| `el-select` | 选择器 |
| `el-switch` | 开关 |
| `el-pagination` | 分页 |

---

## 测试规范

### 测试配置

```javascript
import { config } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import happyDom from 'happy-dom'

config.global.plugins = [
  createPinia()
]

beforeEach(() => {
  setActivePinia(createPinia())
})
```

### API 测试

```javascript
import { describe, it, expect, vi } from 'vitest'
import { getInstanceList, createInstance } from '@/api/instance'
import request from '@/utils/request'

vi.mock('@/utils/request')

describe('Instance API', () => {
  it('should get instance list', async () => {
    const mockData = {
      records: [{ id: 1, name: 'test' }],
      total: 1
    }
    request.mockResolvedValue({ data: mockData })
    
    const result = await getInstanceList({ current: 1, size: 10 })
    
    expect(request).toHaveBeenCalledWith({
      url: '/instances',
      method: 'get',
      params: { current: 1, size: 10 }
    })
    expect(result.data).toEqual(mockData)
  })

  it('should create instance', async () => {
    const mockData = { id: 1 }
    request.mockResolvedValue({ data: mockData })
    
    const result = await createInstance({ name: 'test' })
    
    expect(request).toHaveBeenCalledWith({
      url: '/instances',
      method: 'post',
      data: { name: 'test' }
    })
    expect(result.data).toEqual(mockData)
  })
})
```

### 组件测试

```javascript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import InstanceCard from '@/components/InstanceCard.vue'

describe('InstanceCard', () => {
  it('should render instance info', () => {
    const instance = {
      id: 1,
      name: 'test-instance',
      gameName: 'Minecraft',
      hostName: 'Server-01',
      port: 25565,
      status: 1
    }
    
    const wrapper = mount(InstanceCard, {
      props: { instance }
    })
    
    expect(wrapper.text()).toContain('test-instance')
    expect(wrapper.text()).toContain('Minecraft')
    expect(wrapper.text()).toContain('Server-01')
    expect(wrapper.text()).toContain('25565')
  })

  it('should emit refresh on start', async () => {
    const instance = { id: 1, status: 0 }
    const wrapper = mount(InstanceCard, {
      props: { instance }
    })
    
    await wrapper.find('.el-button--success').trigger('click')
    
    expect(wrapper.emitted('refresh')).toBeTruthy()
  })
})
```

---

## 样式规范

### 样式变量

```scss
:root {
  --primary-color: #409EFF;
  --success-color: #67C23A;
  --warning-color: #E6A23C;
  --danger-color: #F56C6C;
  --info-color: #909399;
  
  --text-primary: #303133;
  --text-regular: #606266;
  --text-secondary: #909399;
  --text-placeholder: #C0C4CC;
  
  --border-color: #DCDFE6;
  --border-radius: 4px;
  
  --spacing-xs: 4px;
  --spacing-sm: 8px;
  --spacing-md: 16px;
  --spacing-lg: 24px;
}
```

### 全局样式

```scss
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  width: 100%;
  height: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  font-size: 14px;
  color: var(--text-primary);
  background-color: #f5f7fa;
}

#app {
  width: 100%;
  height: 100%;
}

a {
  color: var(--primary-color);
  text-decoration: none;
  
  &:hover {
    text-decoration: underline;
  }
}

::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-thumb {
  background-color: var(--border-color);
  border-radius: 3px;
  
  &:hover {
    background-color: var(--text-secondary);
  }
}
```

---

## 常用命令

```bash
# 安装依赖
npm install

# 开发模式
npm run dev

# 构建生产版本
npm run build

# 预览生产版本
npm run preview

# 运行测试
npm run test

# 运行测试(单次)
npm run test:run

# 测试覆盖率
npm run test:coverage

# 代码检查
npm run lint
```

---

## 注意事项

1. **组件库**: 使用 Element Plus，已配置自动导入
2. **状态管理**: 使用 Pinia，避免使用 Vuex
3. **路由**: 使用 Vue Router 4.x
4. **HTTP请求**: 使用 Axios，已封装拦截器
5. **WebSocket**: 已封装重连机制
6. **样式**: 使用 SCSS，遵循 BEM 命名规范
7. **国际化**: 使用 Element Plus 内置中文语言包

---

## 相关文档

- [项目总览](../README.md)
- [AI Agent 协作指南](../AGENTS.md)
- [后端开发指南](../backend/AGENTS.md)
- [API接口文档](../docs/api/api-doc.md)
- [UI/UE设计规范](../docs/design/ui-design-spec.md)
- [ADR 决策记录](../docs/design/adr/README.md)
  - [ADR-0003 废弃 plugin-l4d2-standalone](../docs/design/adr/0003-deprecate-plugin-l4d2-standalone.md)
- [UI 测试文档](../docs/testing/ui-testing/README.md)

---

*最后更新: 2026-08-03*
