# plugin-l4d2-standalone 启动默认展示 UI 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 plugin-l4d2-standalone 启动后默认展示 UI，同一份前端代码既支持 Wujie 插件模式也支持 standalone 独立部署。

**Architecture:** standalone 依赖 plugin-l4d2-core（compile scope），core JAR 中的 `ui/` 构建产物在 standalone classpath 中。后端通过 `WebMvcConfigurer` 把 `classpath:/ui/` 映射为静态资源并实现 SPA fallback；前端通过 `detectMode()` 区分三种运行模式，standalone/dev 模式下显示实例选择页，用户选择实例后进入 L4D2 业务页面。

**Tech Stack:** Spring Boot 3.2.5 / Vue 3.4 + Vite 5 + Element Plus 2.6 + Pinia 2.1 + Vitest 1.4

---

## File Structure

### 新增文件

| 文件 | 职责 |
|------|------|
| `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/.../standalone/config/StandaloneWebConfig.java` | 静态资源映射 `/ui/**` → `classpath:/ui/` + SPA fallback |
| `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/.../standalone/config/StandaloneSpaController.java` | `GET /` 和 `GET /ui` 重定向到 `/ui/index.html` |
| `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/.../standalone/config/StandaloneWebConfigTest.java` | 后端测试：重定向 + SPA fallback |
| `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/resources/ui/index.html` | 测试用前端入口文件 |
| `backend/plugin-l4d2/frontend/src/utils/runtime.ts` | 模式检测 `detectMode()` |
| `backend/plugin-l4d2/frontend/src/utils/runtime.test.ts` | 模式检测测试 |
| `backend/plugin-l4d2/frontend/src/api/standalone.ts` | standalone API 模块（`/api/standalone/*`） |
| `backend/plugin-l4d2/frontend/src/pages/InstanceSelect.vue` | 实例选择页 |
| `backend/plugin-l4d2/frontend/src/pages/InstanceSelect.test.ts` | 实例选择页测试 |
| `backend/plugin-l4d2/frontend/vitest.config.ts` | Vitest 配置 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `backend/plugin-l4d2/frontend/package.json` | 添加 vitest/jsdom 依赖 + test 脚本 |
| `backend/plugin-l4d2/frontend/src/types/index.ts` | 添加 `StandaloneInstance` 类型 |
| `backend/plugin-l4d2/frontend/src/stores/plugin.ts` | 新增 `syncFromStandalone()` 方法 |
| `backend/plugin-l4d2/frontend/src/router/index.ts` | 新增 `/instance-select` 路由 + 三种 base 路径 |
| `backend/plugin-l4d2/frontend/src/main.ts` | Wujie 模式才 initSDK + 添加路由守卫 |

---

## Task 1: 后端 — 静态资源映射 + SPA fallback + 根路径重定向

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneWebConfig.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneSpaController.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneWebConfigTest.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/resources/ui/index.html`

- [ ] **Step 1: 创建 StandaloneWebConfig.java**

```java
package com.gameplatform.plugin.l4d2.standalone.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.util.List;

/**
 * 独立运行静态资源配置。
 * <p>
 * 把 classpath:/ui/（来自 plugin-l4d2-core JAR）映射到 /ui/**，
 * 并实现 SPA fallback：非静态文件路径返回 index.html。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Configuration
public class StandaloneWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**")
                .addResourceLocations("classpath:/ui/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource resolveResourceInternal(HttpServletRequest request,
                            @NonNull String requestPath,
                            @NonNull List<? extends Resource> locations,
                            @NonNull ResourceResolverChain chain) {
                        // 先尝试解析为静态文件
                        Resource resource = super.resolveResourceInternal(
                                request, requestPath, locations, chain);
                        if (resource != null) {
                            return resource;
                        }
                        // SPA fallback：路径不含 "." 视为前端路由，返回 index.html
                        if (!requestPath.contains(".")) {
                            return new ClassPathResource("ui/index.html");
                        }
                        return null;
                    }
                });
    }
}
```

- [ ] **Step 2: 创建 StandaloneSpaController.java**

```java
package com.gameplatform.plugin.l4d2.standalone.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 入口控制器。
 * <p>
 * 根路径重定向到 /ui/index.html，让 standalone 启动后默认展示 UI。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Controller
public class StandaloneSpaController {

    @GetMapping("/")
    public String root() {
        return "redirect:/ui/index.html";
    }

    @GetMapping("/ui")
    public String ui() {
        return "redirect:/ui/index.html";
    }
}
```

- [ ] **Step 3: 创建测试用 index.html**

```
backend/plugin-l4d2/plugin-l4d2-standalone/src/test/resources/ui/index.html
```

文件内容：

```html
<!DOCTYPE html>
<html>
<head><title>L4D2 Test</title></head>
<body><div id="app">test-ui</div></body>
</html>
```

- [ ] **Step 4: 创建 StandaloneWebConfigTest.java**

```java
package com.gameplatform.plugin.l4d2.standalone.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * StandaloneWebConfig + StandaloneSpaController 集成测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StandaloneWebConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void root_redirectsToUiIndex() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/index.html"));
    }

    @Test
    void ui_redirectsToUiIndex() throws Exception {
        mockMvc.perform(get("/ui"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/index.html"));
    }

    @Test
    void uiIndex_returnsHtml() throws Exception {
        mockMvc.perform(get("/ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("test-ui")));
    }

    @Test
    void spaFallback_returnsIndexForRouteWithoutExtension() throws Exception {
        mockMvc.perform(get("/ui/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("test-ui")));
    }

    @Test
    void spaFallback_returnsIndexForMultiLevelRoute() throws Exception {
        mockMvc.perform(get("/ui/instance-select"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("test-ui")));
    }
}
```

- [ ] **Step 5: 创建 application-test.yml**

检查 `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/resources/application-test.yml` 是否存在。如果不存在则创建：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:test-web;MODE=MySQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  sql:
    init:
      schema-locations: classpath:db/schema.sql
      mode: always
  thymeleaf:
    cache: false

server:
  port: 0
```

同时检查 `src/test/resources/db/schema.sql` 是否存在。如果不存在，从 `src/main/resources/db/schema.sql` 复制。

- [ ] **Step 6: 验证编译**

Run: `cd backend && mvn clean compile -pl plugin-l4d2/plugin-l4d2-standalone -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: 运行测试**

Run: `cd backend && mvn test -pl plugin-l4d2/plugin-l4d2-standalone -Dtest=StandaloneWebConfigTest -q`
Expected: 5 个测试通过

- [ ] **Step 8: 提交**

```bash
cd backend
git add plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneWebConfig.java
git add plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneSpaController.java
git add plugin-l4d2/plugin-l4d2-standalone/src/test/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneWebConfigTest.java
git add plugin-l4d2/plugin-l4d2-standalone/src/test/resources/ui/index.html
git add plugin-l4d2/plugin-l4d2-standalone/src/test/resources/application-test.yml
git commit -m "feat(standalone): add static resource mapping and SPA fallback for default UI"
```

---

## Task 2: 前端 — Vitest 配置 + 模式检测 + 测试

**Files:**
- Create: `backend/plugin-l4d2/frontend/vitest.config.ts`
- Create: `backend/plugin-l4d2/frontend/src/utils/runtime.ts`
- Create: `backend/plugin-l4d2/frontend/src/utils/runtime.test.ts`
- Modify: `backend/plugin-l4d2/frontend/package.json`

- [ ] **Step 1: 安装 vitest 和 jsdom**

Run: `cd backend/plugin-l4d2/frontend && npm install -D vitest@^1.4.0 jsdom@^24.0.0`
Expected: 安装成功

- [ ] **Step 2: 创建 vitest.config.ts**

```typescript
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  test: {
    environment: 'jsdom',
    globals: true
  }
})
```

- [ ] **Step 3: 修改 package.json 添加 test 脚本**

在 `scripts` 中添加 `test` 和 `test:watch`：

```json
{
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview",
    "lint": "eslint . --ext .vue,.ts,.tsx --fix",
    "test": "vitest run",
    "test:watch": "vitest"
  }
}
```

- [ ] **Step 4: 创建 runtime.ts**

```typescript
/**
 * 运行模式检测
 */

export type RuntimeMode = 'wujie' | 'standalone' | 'dev'

/**
 * 检测当前运行模式。
 * - wujie: 运行在 Wujie 微前端环境中（被主应用加载）
 * - dev: Vite 开发模式
 * - standalone: standalone 独立部署模式
 */
export function detectMode(): RuntimeMode {
  if (window.__POWERED_BY_WUJIE__) return 'wujie'
  if (import.meta.env.DEV) return 'dev'
  return 'standalone'
}
```

- [ ] **Step 5: 创建 runtime.test.ts**

```typescript
import { describe, it, expect, beforeEach } from 'vitest'
import { detectMode } from './runtime'

describe('detectMode', () => {
  beforeEach(() => {
    delete (window as any).__POWERED_BY_WUJIE__
  })

  it('returns wujie when __POWERED_BY_WUJIE__ is true', () => {
    ;(window as any).__POWERED_BY_WUJIE__ = true
    expect(detectMode()).toBe('wujie')
  })

  it('returns wujie when __POWERED_BY_WUJIE__ is false but truthy', () => {
    ;(window as any).__POWERED_BY_WUJIE__ = false
    // false is falsy, so not wujie
    expect(detectMode()).not.toBe('wujie')
  })

  it('returns dev when not in wujie (vitest runs in dev mode)', () => {
    // vitest 中 import.meta.env.DEV 为 true
    expect(detectMode()).toBe('dev')
  })
})
```

- [ ] **Step 6: 运行测试**

Run: `cd backend/plugin-l4d2/frontend && npx vitest run src/utils/runtime.test.ts`
Expected: 3 个测试通过

- [ ] **Step 7: 提交**

```bash
cd backend
git add plugin-l4d2/frontend/vitest.config.ts
git add plugin-l4d2/frontend/package.json
git add plugin-l4d2/frontend/package-lock.json
git add plugin-l4d2/frontend/src/utils/runtime.ts
git add plugin-l4d2/frontend/src/utils/runtime.test.ts
git commit -m "feat(frontend): add vitest setup and runtime mode detection"
```

---

## Task 3: 前端 — Standalone API 模块 + 类型定义

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/types/index.ts`
- Create: `backend/plugin-l4d2/frontend/src/api/standalone.ts`

- [ ] **Step 1: 在 types/index.ts 末尾添加 StandaloneInstance 类型**

在 `export interface InstanceInfo` 之后添加：

```typescript
// Standalone 模式实例信息（来自 /api/standalone/instances）
export interface StandaloneInstance {
  id: number
  name: string
  hostId: number
  gameCode: string
  installPath: string
  deployType: string
  runStatus: number
  portConfig: string | null
  configInfo: string | null
  startCommand: string | null
  stopCommand: string | null
  remark: string | null
  createdAt: string
  updatedAt: string
}
```

- [ ] **Step 2: 创建 api/standalone.ts**

```typescript
/**
 * Standalone 模式 API 封装。
 * 独立于 @/api/request（后者 base 为 /api/plugin/l4d2），
 * 这里 base 为 /api/standalone。
 */
import type { ApiResponse, StandaloneInstance } from '@/types'

const STANDALONE_API_BASE = '/api/standalone'

/**
 * 通用请求方法
 */
async function standaloneRequest<T>(url: string): Promise<T> {
  const response = await fetch(`${STANDALONE_API_BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' }
  })

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }

  const result: ApiResponse<T> = await response.json()

  if (result.code !== 200) {
    throw new Error(result.message || 'Request failed')
  }

  return result.data
}

/**
 * Standalone API
 */
export const standaloneApi = {
  // 获取实例列表
  getInstances: (): Promise<StandaloneInstance[]> =>
    standaloneRequest<StandaloneInstance[]>('/instances'),

  // 获取单个实例
  getInstance: (id: number): Promise<StandaloneInstance> =>
    standaloneRequest<StandaloneInstance>(`/instances/${id}`)
}

export default standaloneApi
```

- [ ] **Step 3: 验证 TypeScript 编译**

Run: `cd backend/plugin-l4d2/frontend && npx vue-tsc --noEmit`
Expected: 无错误

- [ ] **Step 4: 提交**

```bash
cd backend
git add plugin-l4d2/frontend/src/types/index.ts
git add plugin-l4d2/frontend/src/api/standalone.ts
git commit -m "feat(frontend): add standalone API module and StandaloneInstance type"
```

---

## Task 4: 前端 — Store 适配（syncFromStandalone）

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/stores/plugin.ts`

- [ ] **Step 1: 在 usePluginStore 中添加 syncFromStandalone 方法**

在 `syncFromWujieProps` 方法之后添加新方法：

```typescript
  /**
   * 从 standalone 实例选择页同步实例信息。
   * @param instance 用户选择的实例
   */
  function syncFromStandalone(instance: { id: number; name: string; hostId: number; gameCode: string }) {
    setInstanceInfo({
      instanceId: instance.id,
      instanceName: instance.name,
      hostId: instance.hostId,
      gameCode: instance.gameCode
    })
  }
```

然后在 `return` 对象中添加 `syncFromStandalone`：

```typescript
  return {
    sdk,
    instanceInfo,
    authInfo,
    themeInfo,
    isReady,
    initSDK,
    destroySDK,
    setInstanceInfo,
    setAuthInfo,
    setTheme,
    syncFromWujieProps,
    syncFromStandalone,
    ready,
    notify,
    notifySuccess,
    notifyError,
    notifyWarning,
    notifyInfo,
    confirm,
    navigate
  }
```

- [ ] **Step 2: 验证 TypeScript 编译**

Run: `cd backend/plugin-l4d2/frontend && npx vue-tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 提交**

```bash
cd backend
git add plugin-l4d2/frontend/src/stores/plugin.ts
git commit -m "feat(frontend): add syncFromStandalone to plugin store"
```

---

## Task 5: 前端 — 实例选择页 + 测试

**Files:**
- Create: `backend/plugin-l4d2/frontend/src/pages/InstanceSelect.vue`
- Create: `backend/plugin-l4d2/frontend/src/pages/InstanceSelect.test.ts`

- [ ] **Step 1: 创建 InstanceSelect.vue**

```vue
<template>
  <div class="instance-select">
    <div class="header">
      <h1>L4D2 服务器管理</h1>
      <p>请选择要管理的游戏实例</p>
    </div>

    <div v-loading="loading" class="content">
      <el-empty v-if="!loading && instances.length === 0" description="暂无实例">
        <template #description>
          <p>请先通过 API 添加主机和实例</p>
          <pre class="api-example">curl -X POST http://localhost:8081/api/standalone/hosts \
  -H "Content-Type: application/json" \
  -d '{"name":"my-host","ip":"192.168.1.100","sshPort":22,"username":"root","password":"secret"}'

curl -X POST http://localhost:8081/api/standalone/instances \
  -H "Content-Type: application/json" \
  -d '{"name":"my-server","hostId":1,"installPath":"/home/l4d2/server"}'</pre>
        </template>
      </el-empty>

      <div v-else class="instance-grid">
        <el-card
          v-for="instance in instances"
          :key="instance.id"
          class="instance-card"
          shadow="hover"
          @click="selectInstance(instance)"
        >
          <template #header>
            <div class="card-header">
              <span>{{ instance.name }}</span>
              <el-tag :type="instance.runStatus === 1 ? 'success' : 'info'" size="small">
                {{ instance.runStatus === 1 ? '运行中' : '已停止' }}
              </el-tag>
            </div>
          </template>
          <div class="card-body">
            <p>主机ID: {{ instance.hostId }}</p>
            <p>游戏: {{ instance.gameCode }}</p>
            <p>部署方式: {{ instance.deployType }}</p>
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { standaloneApi } from '@/api/standalone'
import { usePluginStore } from '@/stores/plugin'
import type { StandaloneInstance } from '@/types'

const router = useRouter()
const pluginStore = usePluginStore()
const instances = ref<StandaloneInstance[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    instances.value = await standaloneApi.getInstances()
  } catch (e) {
    ElMessage.error('获取实例列表失败: ' + (e as Error).message)
  } finally {
    loading.value = false
  }
})

function selectInstance(instance: StandaloneInstance) {
  pluginStore.syncFromStandalone(instance)
  router.push('/dashboard')
}
</script>

<style scoped>
.instance-select {
  min-height: 100vh;
  padding: 40px;
  background-color: var(--el-bg-color-page);
}

.header {
  text-align: center;
  margin-bottom: 40px;
}

.header h1 {
  font-size: 28px;
  color: var(--el-text-color-primary);
  margin: 0 0 8px;
}

.header p {
  color: var(--el-text-color-secondary);
  margin: 0;
}

.content {
  max-width: 1200px;
  margin: 0 auto;
}

.instance-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
}

.instance-card {
  cursor: pointer;
  transition: transform 0.2s;
}

.instance-card:hover {
  transform: translateY(-4px);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-body p {
  margin: 4px 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.api-example {
  background: var(--el-fill-color-dark);
  padding: 12px;
  border-radius: 4px;
  font-size: 12px;
  text-align: left;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
```

- [ ] **Step 2: 创建 InstanceSelect.test.ts**

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import InstanceSelect from './InstanceSelect.vue'
import { standaloneApi } from '@/api/standalone'

// mock standaloneApi
vi.mock('@/api/standalone')

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/dashboard' },
      { path: '/instance-select', name: 'InstanceSelect', component: InstanceSelect },
      { path: '/dashboard', name: 'Dashboard', component: { template: '<div>dashboard</div>' } }
    ]
  })
}

function mountComponent() {
  return mount(InstanceSelect, {
    global: {
      plugins: [createPinia(), createTestRouter(), ElementPlus]
    }
  })
}

describe('InstanceSelect', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows empty state when no instances', async () => {
    vi.mocked(standaloneApi.getInstances).mockResolvedValue([])

    const wrapper = mountComponent()
    await flushPromises()

    expect(wrapper.find('.el-empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无实例')
  })

  it('shows instance cards when instances exist', async () => {
    vi.mocked(standaloneApi.getInstances).mockResolvedValue([
      {
        id: 1,
        name: 'test-server',
        hostId: 1,
        gameCode: 'l4d2',
        installPath: '/home/l4d2',
        deployType: 'native',
        runStatus: 0,
        portConfig: null,
        configInfo: null,
        startCommand: null,
        stopCommand: null,
        remark: null,
        createdAt: '2026-07-15T00:00:00',
        updatedAt: '2026-07-15T00:00:00'
      }
    ])

    const wrapper = mountComponent()
    await flushPromises()

    expect(wrapper.findAll('.instance-card')).toHaveLength(1)
    expect(wrapper.text()).toContain('test-server')
  })

  it('shows error message when API fails', async () => {
    vi.mocked(standaloneApi.getInstances).mockRejectedValue(new Error('Network error'))

    const wrapper = mountComponent()
    await flushPromises()

    // ElMessage 是全局的，不会出现在 wrapper 中
    // 但 loading 应该已结束
    expect(wrapper.find('.el-empty').exists()).toBe(true)
  })
})
```

- [ ] **Step 3: 运行测试**

Run: `cd backend/plugin-l4d2/frontend && npx vitest run src/pages/InstanceSelect.test.ts`
Expected: 3 个测试通过

- [ ] **Step 4: 提交**

```bash
cd backend
git add plugin-l4d2/frontend/src/pages/InstanceSelect.vue
git add plugin-l4d2/frontend/src/pages/InstanceSelect.test.ts
git commit -m "feat(frontend): add InstanceSelect page with empty state and instance cards"
```

---

## Task 6: 前端 — 路由适配 + main.ts 适配

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/router/index.ts`
- Modify: `backend/plugin-l4d2/frontend/src/main.ts`

- [ ] **Step 1: 修改 router/index.ts — 添加 instance-select 路由**

在 `routes` 数组中，在 `path: '/'` 之后添加 instance-select 路由：

```typescript
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/instance-select',
    name: 'InstanceSelect',
    component: () => import('@/pages/InstanceSelect.vue'),
    meta: { title: '选择实例', hidden: true }
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/pages/Dashboard.vue'),
    meta: { title: '仪表盘', icon: 'Odometer' }
  },
  // ... 其余路由保持不变
]
```

- [ ] **Step 2: 修改 router/index.ts — 调整 createPluginRouter 的 history base**

将 `createPluginRouter` 函数中的 history 选择逻辑改为三种模式：

```typescript
export function createPluginRouter(props: Record<string, any> = {}): Router {
  const isWujie = typeof window !== 'undefined' && Boolean(window.__POWERED_BY_WUJIE__)
  const isDev = typeof window !== 'undefined' && Boolean(import.meta.env.DEV)

  // Wujie: hash 路由；dev: 根路径；standalone: /ui/ 前缀
  const history = isWujie
    ? createWebHashHistory()
    : isDev
      ? createWebHistory('/')
      : createWebHistory('/ui/')

  const router = createRouter({
    history,
    routes
  })

  // 如果主应用通过 props.route 指定了初始路由，则进行替换
  const initialRoute = props?.route
  if (initialRoute && initialRoute !== '/') {
    router.replace(initialRoute).catch(() => {
      // 忽略重复导航错误
    })
  }

  return router
}
```

- [ ] **Step 3: 修改 main.ts — Wujie 模式才 initSDK + 添加路由守卫**

在 `main.ts` 顶部添加 import：

```typescript
import { detectMode } from './utils/runtime'
```

将 `render` 函数中的 store 初始化和路由部分修改为：

```typescript
function render(props: Record<string, any> = {}): void {
  // 如果已有实例，先卸载，避免重复挂载
  if (app) {
    destroyApp()
  }

  app = createApp(App)

  // 注册所有 Element Plus 图标
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
  }

  // 创建并使用 Pinia
  const pinia = createPinia()
  app.use(pinia)

  // 初始化插件 store
  pluginStore = usePluginStore()

  // Wujie 模式初始化 SDK 与 props 同步；standalone/dev 模式跳过
  const mode = detectMode()
  if (mode === 'wujie') {
    pluginStore.initSDK()
    if (window.$wujie?.props) {
      pluginStore.syncFromWujieProps()
    }
  }

  // 创建路由实例
  const router = createPluginRouter(props)

  // Standalone/dev 模式：添加路由守卫，未选实例时重定向到实例选择页
  if (mode !== 'wujie') {
    router.beforeEach((to, from, next) => {
      if (to.path === '/instance-select') {
        next()
        return
      }
      if (!pluginStore!.instanceInfo) {
        next('/instance-select')
        return
      }
      next()
    })
  }

  app.use(router)
  app.use(ElementPlus)

  // 决定挂载容器
  const container = props.container
    ? (typeof props.container === 'string'
        ? document.querySelector(props.container)
        : props.container)
    : document.getElementById('app')

  if (!container) {
    throw new Error('[L4D2 Plugin] 找不到挂载容器')
  }

  app.mount(container)
}
```

- [ ] **Step 4: 验证 TypeScript 编译**

Run: `cd backend/plugin-l4d2/frontend && npx vue-tsc --noEmit`
Expected: 无错误

- [ ] **Step 5: 运行全部前端测试**

Run: `cd backend/plugin-l4d2/frontend && npx vitest run`
Expected: 全部测试通过

- [ ] **Step 6: 构建前端**

Run: `cd backend/plugin-l4d2/frontend && npm run build`
Expected: 构建成功，输出到 `../src/main/resources/ui/`（即 plugin-l4d2-core 的 ui 目录）

- [ ] **Step 7: 提交**

```bash
cd backend
git add plugin-l4d2/frontend/src/router/index.ts
git add plugin-l4d2/frontend/src/main.ts
git add plugin-l4d2/frontend/src/main/resources/ui/
git commit -m "feat(frontend): add instance-select route, router guard, and standalone base path"
```

---

## Task 7: 全量验证

- [ ] **Step 1: 全量后端编译**

Run: `cd backend && mvn clean compile -pl plugin-l4d2 -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 全量后端测试**

Run: `cd backend && mvn test -pl plugin-l4d2/plugin-l4d2-standalone -q`
Expected: 全部测试通过（含原有 16 个 + 新增 5 个 StandaloneWebConfigTest）

- [ ] **Step 3: 主应用回归测试**

Run: `cd backend && mvn test -pl core -am -q`
Expected: 361 个测试全部通过，无回归

- [ ] **Step 4: 打包 standalone fat JAR**

Run: `cd backend && mvn package -pl plugin-l4d2/plugin-l4d2-standalone -am -DskipTests -q`
Expected: BUILD SUCCESS，生成 `plugin-l4d2-standalone-1.0.0.jar`

- [ ] **Step 5: 验证 fat JAR 包含 ui 资源**

Run: `cd backend/plugin-l4d2/plugin-l4d2-standalone/target && jar tf plugin-l4d2-standalone-1.0.0.jar | findstr "ui/index.html"`
Expected: 输出 `BOOT-INF/classes/ui/index.html`

- [ ] **Step 6: 启动 standalone 应用验证**

Run: `cd backend/plugin-l4d2/plugin-l4d2-standalone/target && java -jar plugin-l4d2-standalone-1.0.0.jar`
（在另一个终端验证）

Expected: 启动成功，监听 8081 端口

- [ ] **Step 7: 浏览器验证**

打开 `http://localhost:8081/`：
- Expected: 自动跳转到 `/ui/index.html`
- Expected: 页面加载 Vue 应用，重定向到 `/ui/instance-select`（因为还没有实例）
- Expected: 显示"暂无实例"空状态

- [ ] **Step 8: API 验证**

```bash
# 添加主机
curl -X POST http://localhost:8081/api/standalone/hosts -H "Content-Type: application/json" -d "{\"name\":\"test\",\"ip\":\"127.0.0.1\",\"sshPort\":22,\"username\":\"root\",\"password\":\"test\"}"

# 添加实例
curl -X POST http://localhost:8081/api/standalone/instances -H "Content-Type: application/json" -d "{\"name\":\"my-server\",\"hostId\":1,\"installPath\":\"/home/l4d2/server\"}"

# 验证实例列表
curl http://localhost:8081/api/standalone/instances
```

刷新浏览器 `http://localhost:8081/ui/instance-select`：
- Expected: 显示实例卡片 "my-server"

- [ ] **Step 9: 选择实例验证**

点击实例卡片：
- Expected: 跳转到 `/ui/dashboard`
- Expected: Dashboard 页面加载（可能因无 SSH 连接报错，但页面框架应正常）

- [ ] **Step 10: SPA fallback 验证**

在浏览器地址栏直接输入 `http://localhost:8081/ui/dashboard` 并回车：
- Expected: 页面正常加载（非 404），回到 Dashboard 或实例选择页

- [ ] **Step 11: 停止应用并提交**

停止 java 进程，然后提交根仓库子模块引用：

```bash
cd d:\program\ai\game_platform_manger
git add backend
git commit -m "feat(standalone): default UI on startup with instance selection"
```

- [ ] **Step 12: 更新文档（可选）**

如果需要，更新 `docs/CODE_WIKI.md` 中 standalone 相关章节，说明启动后默认展示 UI。

---

## Self-Review Notes

### Spec coverage

| Spec 章节 | 对应 Task |
|-----------|----------|
| §4.1 StandaloneWebConfig | Task 1 Step 1 |
| §4.2 StandaloneSpaController | Task 1 Step 2 |
| §4.4 验证点 | Task 7 Step 7/10 |
| §5.1 模式检测 | Task 2 Step 4 |
| §5.2 实例选择页 | Task 5 Step 1 |
| §5.3 路由适配 | Task 6 Step 1/2 |
| §5.4 API 适配 | Task 3 Step 2 |
| §5.5 store 适配 | Task 4 Step 1 |
| §5.6 main.ts 适配 | Task 6 Step 3 |
| §6.4 测试策略 | Task 1 Step 4 + Task 2 Step 5 + Task 5 Step 2 |
| §7 验收清单 | Task 7 全部步骤 |

### Type consistency

- `detectMode()` 返回 `RuntimeMode = 'wujie' | 'standalone' | 'dev'` — Task 2 定义，Task 6 使用
- `syncFromStandalone(instance: { id, name, hostId, gameCode })` — Task 4 定义，Task 5 调用时传 `StandaloneInstance`（兼容，因为 StandaloneInstance 包含这四个字段）
- `StandaloneInstance` 类型 — Task 3 定义，Task 5 使用
- `standaloneApi.getInstances()` 返回 `Promise<StandaloneInstance[]>` — Task 3 定义，Task 5 调用

### 已知限制

- Vitest 中 `import.meta.env.DEV` 始终为 true，无法测试 `standalone` 模式检测（只能测 `wujie` 和 `dev`）
- `App.vue` 的 `onMounted` 仍会调用 `pluginStore.initSDK()` 和 `pluginStore.ready()`，在 standalone 模式下是无害的空操作（SDK 只记录日志），不修改 `App.vue` 以保持最小改动
- 测试用 `index.html` 仅包含简单 HTML，不包含实际 Vue 应用，测试仅验证 HTTP 层行为
