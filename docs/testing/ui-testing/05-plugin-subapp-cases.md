# 05 - 插件子应用测试用例

> plugin-l4d2/frontend/ 测试用例规范

---

## 1. 现有测试用例

### 1.1 单元测试（plugin-l4d2/frontend/src/）

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `utils/runtime.test.ts` | runtime.ts | 模式检测：wujie / dev（ADR-0003，v3.3.0 起 standalone 模式已废弃） |

### 1.2 待补充用例（按优先级）

#### 高优先级

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `utils/gameConstants.test.ts` | gameConstants.ts | 战役名称映射、章节解析、地图类型判断 |
| `utils/statusParser.test.ts` | statusParser.ts | 服务器状态解析、玩家列表解析、空状态处理 |
| `utils/pluginSDK.test.ts` | pluginSDK.ts | SDK 方法调用、错误降级 |
| `api/index.test.ts` | api/index.ts | 请求封装、错误处理、token 注入 |
| `api/request.test.ts` | api/request.ts | baseURL 拼接、401 处理、超时 |

#### 中优先级

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `pages/Dashboard.test.ts` | Dashboard.vue | 状态显示、刷新、玩家列表 |
| `pages/Rcon.test.ts` | Rcon.vue | 命令执行、踢人、封禁 |
| `pages/Maps.test.ts` | Maps.vue | VPK 列表、地图切换、批量操作 |
| `pages/Admins.test.ts` | Admins.vue | 管理员 CRUD、权限选择 |
| `pages/ServerConfig.test.ts` | ServerConfig.vue | 配置加载、保存、表单校验 |

#### 低优先级

| 文件 | 测试对象 | 关键用例 |
|------|----------|----------|
| `pages/Restart.test.ts` | Restart.vue | 重启模式选择、防注入校验 |
| `pages/Backup.test.ts` | Backup.vue | 备份列表、还原 |
| `pages/Logs.test.ts` | Logs.vue | 日志列表、查看 |
| `pages/Monitor.test.ts` | Monitor.vue | 实时数据、图表 |
| `pages/PlayerStats.test.ts` | PlayerStats.vue | 玩家统计、趋势图 |
| `pages/Playtime.test.ts` | Playtime.vue | 游戏时长统计 |
| `components/plugins/RemoteStoreTab.test.ts` | RemoteStoreTab.vue | 远端仓库列表、行内展开详情、下载（ADR-0022 并入插件管理三 Tab） |
| `pages/PluginConfig.test.ts` | PluginConfig.vue | 插件配置编辑 |
| `pages/Preset.test.ts` | Preset.vue | 预设应用 |
| `pages/ServerInfo.test.ts` | ServerInfo.vue | 服务器信息编辑 |
| `pages/VersionInfo.test.ts` | VersionInfo.vue | 版本信息展示 |

---

## 2. 测试模板

### 2.1 页面测试模板（Wujie 模式）

```typescript
// pages/ExamplePage.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createWebHashHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ExamplePage from './ExamplePage.vue'

// Mock API
vi.mock('@/api', () => ({
  exampleApi: {
    getList: vi.fn(),
    create: vi.fn()
  }
}))

// Mock plugin store（Wujie 模式下从 props 获取数据）
vi.mock('@/stores/plugin', () => ({
  usePluginStore: () => ({
    instance: { id: 1, gameCode: 'l4d2', hostIp: '127.0.0.1' },
    auth: { token: 'test-token' }
  })
}))

describe('ExamplePage', () => {
  let router: any

  beforeEach(() => {
    setActivePinia(createPinia())
    // 模拟 Wujie 环境
    ;(window as any).__POWERED_BY_WUJIE__ = true
    router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/', component: ExamplePage }]
    })
  })

  const mountPage = async () => {
    const wrapper = mount(ExamplePage, {
      global: {
        plugins: [ElementPlus, router]
      }
    })
    await router.isReady()
    await flushPromises()
    return wrapper
  }

  it('应该在加载时调用 getList', async () => {
    const { exampleApi } = await import('@/api')
    exampleApi.getList.mockResolvedValue({ data: [] })
    
    await mountPage()
    
    expect(exampleApi.getList).toHaveBeenCalled()
  })

  it('应该正确显示空状态', async () => {
    const { exampleApi } = await import('@/api')
    exampleApi.getList.mockResolvedValue({ data: [] })
    
    const wrapper = await mountPage()
    
    expect(wrapper.find('.el-empty').exists()).toBe(true)
  })
})
```

### 2.2 工具函数测试模板

```typescript
// utils/gameConstants.test.ts
import { describe, it, expect } from 'vitest'
import {
  CAMPAIGN_NAMES,
  parseMapName,
  getMapDisplayName,
  isCustomMap
} from './gameConstants'

describe('gameConstants', () => {
  describe('parseMapName', () => {
    it('应该正确解析官方地图 c1m1_hotel', () => {
      const result = parseMapName('c1m1_hotel')
      expect(result).toEqual({
        campaign: '死亡中心',
        chapter: 1,
        displayName: '死亡中心 - 第1章',
        isCustom: false
      })
    })

    it('应该正确解析自定义地图', () => {
      const result = parseMapName('custom_map_v2')
      expect(result.isCustom).toBe(true)
      expect(result.campaign).toBe('自定义')
    })

    it('应该处理空字符串', () => {
      const result = parseMapName('')
      expect(result.campaign).toBe('未知')
    })
  })

  describe('getMapDisplayName', () => {
    it('应该返回中文名称', () => {
      expect(getMapDisplayName('c1m1_hotel')).toContain('死亡中心')
    })
  })

  describe('isCustomMap', () => {
    it('c1m1 应该是官方地图', () => {
      expect(isCustomMap('c1m1_hotel')).toBe(false)
    })

    it('custom_map 应该是自定义地图', () => {
      expect(isCustomMap('custom_map')).toBe(true)
    })
  })
})
```

### 2.3 组件测试模板

```typescript
// components/MapSelectorModal.test.ts
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import MapSelectorModal from './MapSelectorModal.vue'

describe('MapSelectorModal', () => {
  const mountComponent = (props: any = {}) => mount(MapSelectorModal, {
    global: { plugins: [ElementPlus] },
    props: {
      modelValue: true,
      ...props
    }
  })

  it('应该在 modelValue=true 时显示', () => {
    const wrapper = mountComponent({ modelValue: true })
    expect(wrapper.find('.el-dialog').isVisible()).toBe(true)
  })

  it('应该在选择地图后触发 confirm 事件', async () => {
    const wrapper = mountComponent({ modelValue: true })
    await wrapper.find('[data-testid=map-item]').trigger('click')
    await wrapper.find('[data-testid=confirm-btn]').trigger('click')
    
    expect(wrapper.emitted('confirm')).toBeTruthy()
    expect(wrapper.emitted('confirm')![0]).toEqual(['c1m1_hotel'])
  })
})
```

---

## 3. 关键场景用例要求

### 3.1 Dashboard

| 用例 | 验证点 |
|------|--------|
| 加载服务器状态 | 调用 status API、显示在线/离线 |
| 显示玩家列表 | 解析 players 数组、显示名称/分数 |
| 刷新按钮 | 触发重新加载、loading 状态 |
| 离线状态 | 显示离线提示、隐藏玩家列表 |

### 3.2 Rcon

| 用例 | 验证点 |
|------|--------|
| 执行命令 | 输入框、回车提交、显示响应 |
| 踢人 | 选择玩家、确认、调用 kick API |
| 封禁 | 选择玩家、输入时长、调用 ban API |
| 切换难度 | 下拉选择、调用 changeDifficulty |
| 切换游戏模式 | 下拉选择、调用 changeGameMode |
| 设置最大玩家数 | 输入数字、调用 setMaxPlayers |
| 切换地图 | 弹出地图选择器、调用 changeMap |

### 3.3 Maps

| 用例 | 验证点 |
|------|--------|
| 加载地图列表 | 调用 maps API、显示 VPK 列表 |
| 解析 VPK | 显示战役名、章节、地图类型 |
| 切换地图 | 点击地图、确认、调用 changeMap |
| 批量操作 | 多选、批量删除、批量启用 |
| 上传 VPK | 文件选择、上传进度、刷新列表 |

### 3.4 ServerConfig

| 用例 | 验证点 |
|------|--------|
| 加载配置 | 调用 getConfig、填充表单 |
| 保存配置 | 表单校验、调用 saveConfig、提示成功 |
| 多 tick 同步 | marker 保留策略、差异更新 |
| 重置配置 | 确认弹窗、恢复默认值 |

### 3.5 PluginConfig（插件配置编辑）

> 语义说明（2026-09 修复后）：候选 cfg 路径优先取插件元数据（plugin.yaml）声明的
> `config_files`（与实际安装文件名一致，中文显示名插件必走此路径），每条声明产生
> "游戏目录副本"（已启用时存在，优先）与"插件库副本"（安装即存在）两个候选；
> 无 meta 时回退按插件名推导。解析兼容带引号/不带引号键，并提取前置注释块中的
> Default/Min/Max/描述；序列化保留原行键引号风格与注释块（保存不损文件）。

| 用例 | 验证点 |
|------|--------|
| 无 cfg 插件 | 返回空配置结构（items 空数组、configPath 空），页面显示空态提示，不报错 |
| 未启用插件编辑 | 命中插件库副本候选，configPath 指向 plugins_store，可查看/编辑/保存 |
| 保存持久化 | update 后重新 GET，修改值落盘；行格式保持 `key "value"`（不重复键） |
| 恢复默认配置 | header 按钮 → 确认弹窗（显示可恢复项数）→ restore-defaults 立即写回 → 表格刷新 |
| 恢复默认边界 | 无 Default 注释的项保持不变；全部已是默认值时提示无可恢复 |
| 已启用插件生效 | 保存 → 重载配置（exec server.cfg）→ RCON 读回实时值 |

---

*最后更新: 2026-09-13（新增 3.5 PluginConfig 用例：meta 候选优先/库副本回退/恢复默认）*
