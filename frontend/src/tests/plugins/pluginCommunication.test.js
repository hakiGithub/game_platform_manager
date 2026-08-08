/**
 * pluginCommunication.ts 测试（Wujie 版）
 * 测试插件通信管理器的所有功能
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ref, nextTick } from 'vue'
import { setActivePinia, createPinia } from 'pinia'

// Mock vue-router
const mockPush = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: mockPush
  })
}))

// Mock element-plus
vi.mock('element-plus', () => ({
  ElMessage: vi.fn(),
  ElMessageBox: {
    confirm: vi.fn()
  }
}))

// Mock stores
vi.mock('@/stores/user', () => ({
  useUserStore: () => ({
    token: 'test-token',
    username: 'admin',
    userInfo: { id: 1, username: 'admin', role: 'admin' },
    permissions: ['user:read', 'user:write']
  })
}))

vi.mock('@/stores/app', () => ({
  useAppStore: () => ({
    theme: 'light'
  })
}))

// Mock request
vi.mock('@/utils/request', () => ({
  default: vi.fn()
}))

// Mock wujie-vue3
// Wujie-vue3 默认导出插件对象，bus 挂在默认导出上
// pluginCommunication.ts 中使用：import WujieVue from 'wujie-vue3'; const { bus } = WujieVue
const mockBus = vi.hoisted(() => ({
  $on: vi.fn(),
  $off: vi.fn(),
  $emit: vi.fn()
}))

vi.mock('wujie-vue3', () => ({
  default: {
    bus: mockBus
  }
}))

// 导入被测试模块
import { usePluginCommunication, fetchPluginManifest } from '@/plugins/communication/pluginCommunication'
import { MessageTypes } from '@/plugins/types/messageTypes'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

describe('pluginCommunication', () => {
  let manifest
  let instanceInfo
  let communication

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()

    manifest = ref(null)
    instanceInfo = ref({
      instanceId: 1,
      instanceName: 'test-instance',
      gameCode: 'minecraft',
      hostId: 1,
      hostIp: '192.168.1.100',
      deployPath: '/opt/minecraft',
      ports: { main: 25565 }
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
    if (communication) {
      communication.destroy()
      communication = null
    }
  })

  describe('bus 事件注册', () => {
    it('初始化时应该注册所有 bus 监听器', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      expect(mockBus.$on).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.READY}`,
        expect.any(Function)
      )
      expect(mockBus.$on).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.NAVIGATE}`,
        expect.any(Function)
      )
      expect(mockBus.$on).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.NOTIFY}`,
        expect.any(Function)
      )
      expect(mockBus.$on).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.CONFIRM}`,
        expect.any(Function)
      )
      expect(mockBus.$on).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.API_REQUEST}`,
        expect.any(Function)
      )
    })

    it('销毁时应该移除所有 bus 监听器', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      communication.destroy()

      expect(mockBus.$off).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.READY}`,
        expect.any(Function)
      )
      expect(mockBus.$off).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.NAVIGATE}`,
        expect.any(Function)
      )
    })
  })

  describe('sendMessage', () => {
    it('应该通过 bus 发送消息', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      communication.sendMessage(MessageTypes.INIT, { instanceId: 1 })

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.INIT}`,
        { instanceId: 1 }
      )
    })
  })

  describe('sendInit', () => {
    it('应该发送初始化消息', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      communication.sendInit()

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.INIT}`,
        instanceInfo.value
      )
    })

    it('实例信息不存在时应该警告', () => {
      const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

      instanceInfo.value = null

      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })
      communication.sendInit()

      expect(consoleSpy).toHaveBeenCalledWith(
        '[PluginCommunication] instance info not ready'
      )

      consoleSpy.mockRestore()
    })
  })

  describe('sendAuth', () => {
    it('应该发送认证消息', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      communication.sendAuth()

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.AUTH}`,
        expect.objectContaining({
          token: 'test-token',
          user: expect.objectContaining({
            id: 1,
            username: 'admin',
            role: 'admin'
          })
        })
      )
    })
  })

  describe('sendThemeChange', () => {
    it('应该发送主题变化消息', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      communication.sendThemeChange()

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.THEME_CHANGE}`,
        {
          isDark: false,
          theme: 'light'
        }
      )
    })
  })

  describe('sendConfirmResult', () => {
    it('应该发送确认结果消息', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      communication.sendConfirmResult('req-123', true)

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.CONFIRM_RESULT}`,
        {
          requestId: 'req-123',
          confirmed: true
        }
      )
    })
  })

  describe('handleReady', () => {
    it('应该处理 READY 事件并触发 onReady 回调', async () => {
      const onReady = vi.fn()

      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo,
        onReady
      })

      // 触发 READY 事件
      const readyHandler = mockBus.$on.mock.calls.find(
        call => call[0] === `plugin-minecraft:${MessageTypes.READY}`
      )[1]

      readyHandler({
        version: '1.0.0',
        capabilities: ['api']
      })

      await nextTick()

      expect(communication.isReady.value).toBe(true)
      expect(onReady).toHaveBeenCalled()
      // READY 后会发送 AUTH 和 THEME_CHANGE
      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.AUTH}`,
        expect.any(Object)
      )
      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.THEME_CHANGE}`,
        expect.any(Object)
      )
    })
  })

  describe('handleNavigate', () => {
    it('应该处理 NAVIGATE 事件', async () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      const navigateHandler = mockBus.$on.mock.calls.find(
        call => call[0] === `plugin-minecraft:${MessageTypes.NAVIGATE}`
      )[1]

      navigateHandler({
        path: '/instances/1',
        query: { tab: 'config' }
      })

      await nextTick()

      expect(mockPush).toHaveBeenCalledWith({
        path: '/instances/1',
        query: { tab: 'config' }
      })
    })
  })

  describe('handleNotify', () => {
    it('应该处理 NOTIFY 事件', async () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      const notifyHandler = mockBus.$on.mock.calls.find(
        call => call[0] === `plugin-minecraft:${MessageTypes.NOTIFY}`
      )[1]

      notifyHandler({
        type: 'success',
        title: '操作成功',
        message: '数据已保存'
      })

      await nextTick()

      expect(ElMessage).toHaveBeenCalledWith(
        expect.objectContaining({
          type: 'success',
          message: '操作成功: 数据已保存'
        })
      )
    })
  })

  describe('handleConfirm', () => {
    it('应该处理 CONFIRM 事件 - 确认', async () => {
      ElMessageBox.confirm.mockResolvedValue()

      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      const confirmHandler = mockBus.$on.mock.calls.find(
        call => call[0] === `plugin-minecraft:${MessageTypes.CONFIRM}`
      )[1]

      confirmHandler({
        requestId: 'req-123',
        title: '确认删除',
        message: '确定要删除吗？'
      })

      await nextTick()
      await new Promise(resolve => setTimeout(resolve, 0))

      expect(ElMessageBox.confirm).toHaveBeenCalledWith(
        '确定要删除吗？',
        '确认删除',
        expect.objectContaining({
          confirmButtonText: '确定',
          cancelButtonText: '取消'
        })
      )

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.CONFIRM_RESULT}`,
        {
          requestId: 'req-123',
          confirmed: true
        }
      )
    })

    it('应该处理 CONFIRM 事件 - 取消', async () => {
      ElMessageBox.confirm.mockRejectedValue(new Error('cancel'))

      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      const confirmHandler = mockBus.$on.mock.calls.find(
        call => call[0] === `plugin-minecraft:${MessageTypes.CONFIRM}`
      )[1]

      confirmHandler({
        requestId: 'req-456',
        title: '确认删除',
        message: '确定要删除吗？'
      })

      await nextTick()
      await new Promise(resolve => setTimeout(resolve, 0))

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.CONFIRM_RESULT}`,
        {
          requestId: 'req-456',
          confirmed: false
        }
      )
    })
  })

  describe('handleApiRequest', () => {
    it('应该处理 API_REQUEST 事件 - 成功', async () => {
      request.mockResolvedValue({ id: 1, name: 'test' })

      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      const apiHandler = mockBus.$on.mock.calls.find(
        call => call[0] === `plugin-minecraft:${MessageTypes.API_REQUEST}`
      )[1]

      apiHandler({
        requestId: 'req-789',
        method: 'GET',
        url: '/api/instances/1'
      })

      await nextTick()
      await new Promise(resolve => setTimeout(resolve, 0))

      expect(request).toHaveBeenCalledWith(
        expect.objectContaining({
          method: 'GET',
          url: '/api/instances/1'
        })
      )

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.API_RESPONSE}`,
        expect.objectContaining({
          requestId: 'req-789',
          success: true,
          data: { id: 1, name: 'test' }
        })
      )
    })

    it('应该处理 API_REQUEST 事件 - 失败', async () => {
      request.mockRejectedValue({
        response: {
          status: 404,
          data: { message: 'Not found' }
        },
        message: 'Request failed'
      })

      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      const apiHandler = mockBus.$on.mock.calls.find(
        call => call[0] === `plugin-minecraft:${MessageTypes.API_REQUEST}`
      )[1]

      apiHandler({
        requestId: 'req-error',
        method: 'GET',
        url: '/api/not-exist'
      })

      await nextTick()
      await new Promise(resolve => setTimeout(resolve, 0))

      expect(mockBus.$emit).toHaveBeenCalledWith(
        `plugin-minecraft:${MessageTypes.API_RESPONSE}`,
        expect.objectContaining({
          requestId: 'req-error',
          success: false,
          error: {
            code: 404,
            message: 'Not found'
          }
        })
      )
    })
  })

  describe('destroy', () => {
    it('应该重置就绪状态', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      communication.isReady.value = true
      communication.destroy()

      expect(communication.isReady.value).toBe(false)
    })
  })

  describe('reinit', () => {
    it('应该重置就绪状态', () => {
      communication = usePluginCommunication({
        name: 'plugin-minecraft',
        manifest,
        instanceInfo
      })

      communication.isReady.value = true
      communication.reinit()

      expect(communication.isReady.value).toBe(false)
    })
  })

  describe('fetchPluginManifest', () => {
    it('应该获取插件清单', async () => {
      // 后端响应格式：gameName、frontend.entry、frontend.menus 等
      const mockBackendResponse = {
        pluginId: 'plugin-1',
        gameName: 'Test Plugin',
        version: '1.0.0',
        gameCode: 'minecraft',
        frontendEntry: '/plugins/test/',
        frontend: {
          entry: '/plugins/test/',
          menus: []
        }
      }

      // 前端期望格式：name、entry、menus 在顶层
      const expectedManifest = {
        pluginId: 'plugin-1',
        name: 'Test Plugin',
        version: '1.0.0',
        gameCode: 'minecraft',
        entry: '/plugins/test/',
        menus: [],
        description: '',
        capabilities: [],
        permissions: []
      }

      request.mockResolvedValue(mockBackendResponse)

      const result = await fetchPluginManifest('minecraft')

      expect(request).toHaveBeenCalledWith({
        url: '/pf4j/plugin/minecraft/manifest',
        method: 'get'
      })

      expect(result).toEqual(expectedManifest)
    })
  })
})
