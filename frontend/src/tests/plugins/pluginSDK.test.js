/**
 * pluginSDK.ts 测试（Wujie 版）
 * 测试插件通信 SDK 的所有功能
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MessageTypes } from '@/plugins/types/messageTypes'

// 先导入 PluginSDK
import { PluginSDK, getPluginSDK, createPluginSDK } from '@/plugins/sdk/pluginSDK'

describe('pluginSDK', () => {
  let sdk
  let mockBus
  let wujieContext

  beforeEach(() => {
    vi.clearAllMocks()

    // Mock Wujie bus
    mockBus = {
      $on: vi.fn(),
      $off: vi.fn(),
      $emit: vi.fn()
    }

    // Mock window.$wujie
    wujieContext = {
      props: {
        instance: {
          instanceId: 1,
          instanceName: 'test',
          gameCode: 'minecraft',
          hostId: 1,
          hostIp: '192.168.1.100',
          deployPath: '/opt/minecraft',
          ports: { main: 25565 }
        },
        auth: {
          token: 'test-token',
          user: {
            id: 1,
            username: 'admin',
            role: 'admin',
            permissions: ['user:read']
          }
        },
        theme: {
          isDark: false,
          theme: 'light'
        },
        baseApi: '/api'
      },
      bus: mockBus
    }

    Object.defineProperty(window, '$wujie', {
      value: wujieContext,
      writable: true,
      configurable: true
    })
  })

  afterEach(() => {
    if (sdk) {
      try {
        sdk.destroy()
      } catch (e) {
        // ignore
      }
      sdk = null
    }
    vi.clearAllMocks()
  })

  describe('构造函数', () => {
    it('应该使用默认选项创建实例', () => {
      sdk = new PluginSDK()

      expect(sdk).toBeDefined()
    })

    it('应该使用自定义选项创建实例', () => {
      sdk = new PluginSDK({
        version: '1.0.0',
        capabilities: ['api', 'storage'],
        autoInit: true,
        debug: true
      })

      expect(sdk).toBeDefined()
    })

    it('autoInit 为 false 时不应该自动初始化', () => {
      sdk = new PluginSDK({ autoInit: false })

      // 手动初始化后才能使用
      sdk.init()
      expect(sdk).toBeDefined()
    })
  })

  describe('init', () => {
    it('应该初始化 SDK 并读取 Wujie props', () => {
      sdk = new PluginSDK({ autoInit: false })

      sdk.init()

      expect(mockBus.$on).toHaveBeenCalled()
      expect(sdk.getInstanceInfo()).toEqual(wujieContext.props.instance)
      expect(sdk.getAuthInfo()).toEqual(wujieContext.props.auth)
      expect(sdk.getThemeInfo()).toEqual(wujieContext.props.theme)
    })

    it('重复初始化应该警告', () => {
      const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

      sdk = new PluginSDK()
      sdk.init()

      expect(consoleSpy).toHaveBeenCalledWith('[PluginSDK] Already initialized')

      consoleSpy.mockRestore()
    })

    it('Wujie 上下文不存在时应该警告', () => {
      const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
      window.$wujie = null

      sdk = new PluginSDK({ autoInit: false })
      sdk.init()

      expect(consoleSpy).toHaveBeenCalledWith('[PluginSDK] Wujie context not available, SDK will not receive events')

      consoleSpy.mockRestore()
    })
  })

  describe('destroy', () => {
    it('应该销毁 SDK', () => {
      sdk = new PluginSDK()

      sdk.destroy()

      expect(mockBus.$off).toHaveBeenCalled()
    })

    it('应该清理所有待处理的请求', async () => {
      sdk = new PluginSDK()

      // 创建一个待处理的确认请求
      const confirmPromise = sdk.confirm('Test', 'Message')

      // 销毁 SDK
      sdk.destroy()

      // 请求应该被拒绝
      await expect(confirmPromise).rejects.toThrow('SDK destroyed')
    })
  })

  describe('ready', () => {
    it('应该通过 bus 发送 READY 事件', () => {
      sdk = new PluginSDK({
        version: '1.0.0',
        capabilities: ['api']
      })

      sdk.ready()

      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:READY',
        {
          version: '1.0.0',
          capabilities: ['api']
        }
      )
    })

    it('重复调用应该警告', () => {
      const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

      sdk = new PluginSDK()
      sdk.ready()
      sdk.ready()

      expect(consoleSpy).toHaveBeenCalledWith('[PluginSDK] Already ready')

      consoleSpy.mockRestore()
    })
  })

  describe('getInstanceInfo', () => {
    it('应该返回 null（初始状态）', () => {
      window.$wujie = null
      sdk = new PluginSDK({ autoInit: false })

      expect(sdk.getInstanceInfo()).toBeNull()
    })

    it('应该返回实例信息（读取 props 后）', () => {
      sdk = new PluginSDK()

      expect(sdk.getInstanceInfo()).toEqual(wujieContext.props.instance)
    })
  })

  describe('getAuthInfo', () => {
    it('应该返回认证信息', () => {
      sdk = new PluginSDK()

      expect(sdk.getAuthInfo()).toEqual(wujieContext.props.auth)
    })
  })

  describe('getThemeInfo', () => {
    it('应该返回主题信息', () => {
      sdk = new PluginSDK()

      expect(sdk.getThemeInfo()).toEqual(wujieContext.props.theme)
    })
  })

  describe('navigate', () => {
    it('应该通过 bus 发送 NAVIGATE 事件', () => {
      sdk = new PluginSDK()

      sdk.navigate('/instances/1', { tab: 'config' })

      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:NAVIGATE',
        {
          path: '/instances/1',
          query: { tab: 'config' }
        }
      )
    })
  })

  describe('notify', () => {
    it('应该通过 bus 发送 NOTIFY 事件', () => {
      sdk = new PluginSDK()

      sdk.notify('success', '操作成功', '数据已保存', 5000)

      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:NOTIFY',
        {
          type: 'success',
          title: '操作成功',
          message: '数据已保存',
          duration: 5000
        }
      )
    })
  })

  describe('notifySuccess', () => {
    it('应该发送成功通知', () => {
      sdk = new PluginSDK()

      sdk.notifySuccess('成功', '操作完成')

      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:NOTIFY',
        expect.objectContaining({
          type: 'success',
          title: '成功',
          message: '操作完成'
        })
      )
    })
  })

  describe('notifyWarning', () => {
    it('应该发送警告通知', () => {
      sdk = new PluginSDK()

      sdk.notifyWarning('警告', '请注意')

      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:NOTIFY',
        expect.objectContaining({
          type: 'warning'
        })
      )
    })
  })

  describe('notifyError', () => {
    it('应该发送错误通知', () => {
      sdk = new PluginSDK()

      sdk.notifyError('错误', '操作失败')

      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:NOTIFY',
        expect.objectContaining({
          type: 'error'
        })
      )
    })
  })

  describe('notifyInfo', () => {
    it('应该发送信息通知', () => {
      sdk = new PluginSDK()

      sdk.notifyInfo('提示', '这是一条信息')

      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:NOTIFY',
        expect.objectContaining({
          type: 'info'
        })
      )
    })
  })

  describe('confirm', () => {
    it('应该发送 CONFIRM 事件并等待结果', async () => {
      sdk = new PluginSDK()

      const confirmPromise = sdk.confirm('确认删除', '确定要删除吗？', {
        confirmText: '删除',
        cancelText: '取消',
        type: 'warning'
      })

      // 验证消息已发送
      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:CONFIRM',
        expect.objectContaining({
          title: '确认删除',
          message: '确定要删除吗？',
          confirmText: '删除',
          cancelText: '取消',
          type: 'warning'
        })
      )

      // 获取 requestId
      const callArgs = mockBus.$emit.mock.calls[0][1]
      const requestId = callArgs.requestId

      // 模拟接收 CONFIRM_RESULT 事件
      sdk.on(MessageTypes.CONFIRM_RESULT, () => {})
      const confirmResultHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:CONFIRM_RESULT'
      )[1]
      confirmResultHandler({
        requestId,
        confirmed: true
      })

      const result = await confirmPromise
      expect(result).toBe(true)
    })

    it('应该处理取消确认', async () => {
      sdk = new PluginSDK()

      const confirmPromise = sdk.confirm('确认删除', '确定要删除吗？')

      // 获取 requestId
      const callArgs = mockBus.$emit.mock.calls[0][1]
      const requestId = callArgs.requestId

      // 模拟接收 CONFIRM_RESULT 事件 - 取消
      const confirmResultHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:CONFIRM_RESULT'
      )[1]
      confirmResultHandler({
        requestId,
        confirmed: false
      })

      const result = await confirmPromise
      expect(result).toBe(false)
    })

    it('应该处理超时', async () => {
      sdk = new PluginSDK({})

      // 使用较短的测试超时
      sdk.confirmTimeout = 100

      const confirmPromise = sdk.confirm('确认', '消息')

      await expect(confirmPromise).rejects.toThrow('Confirm timeout')
    })
  })

  describe('request', () => {
    it('应该发送 API_REQUEST 事件并等待响应', async () => {
      sdk = new PluginSDK()

      const requestPromise = sdk.request('GET', '/api/instances/1')

      // 验证消息已发送
      expect(mockBus.$emit).toHaveBeenCalledWith(
        'plugin-minecraft:API_REQUEST',
        expect.objectContaining({
          method: 'GET',
          url: '/api/instances/1'
        })
      )

      // 获取 requestId
      const callArgs = mockBus.$emit.mock.calls[0][1]
      const requestId = callArgs.requestId

      // 模拟接收 API_RESPONSE 事件
      const apiResponseHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:API_RESPONSE'
      )[1]
      apiResponseHandler({
        requestId,
        success: true,
        data: { id: 1, name: 'test' }
      })

      const result = await requestPromise
      expect(result).toEqual({ id: 1, name: 'test' })
    })

    it('应该处理 API 错误响应', async () => {
      sdk = new PluginSDK()

      const requestPromise = sdk.request('GET', '/api/not-exist')

      // 获取 requestId
      const callArgs = mockBus.$emit.mock.calls[0][1]
      const requestId = callArgs.requestId

      // 模拟接收 API_RESPONSE 事件 - 失败
      const apiResponseHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:API_RESPONSE'
      )[1]
      apiResponseHandler({
        requestId,
        success: false,
        error: {
          code: 404,
          message: 'Not found'
        }
      })

      await expect(requestPromise).rejects.toThrow('Not found')
    })

    it('应该处理超时', async () => {
      sdk = new PluginSDK()

      // 使用较短的测试超时
      sdk.apiTimeout = 100

      const requestPromise = sdk.request('GET', '/api/test')

      await expect(requestPromise).rejects.toThrow('API request timeout')
    })
  })

  describe('get', () => {
    it('应该发送 GET 请求', async () => {
      sdk = new PluginSDK()

      const requestPromise = sdk.get('/api/instances', { page: 1 })

      const callArgs = mockBus.$emit.mock.calls[0][1]
      expect(callArgs.method).toBe('GET')
      expect(callArgs.url).toBe('/api/instances')
      expect(callArgs.params).toEqual({ page: 1 })

      // 模拟响应
      const requestId = callArgs.requestId
      const apiResponseHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:API_RESPONSE'
      )[1]
      apiResponseHandler({ requestId, success: true, data: [] })

      await requestPromise
    })
  })

  describe('post', () => {
    it('应该发送 POST 请求', async () => {
      sdk = new PluginSDK()

      const requestPromise = sdk.post('/api/instances', { name: 'test' })

      const callArgs = mockBus.$emit.mock.calls[0][1]
      expect(callArgs.method).toBe('POST')
      expect(callArgs.data).toEqual({ name: 'test' })

      // 模拟响应
      const requestId = callArgs.requestId
      const apiResponseHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:API_RESPONSE'
      )[1]
      apiResponseHandler({ requestId, success: true, data: { id: 1 } })

      await requestPromise
    })
  })

  describe('put', () => {
    it('应该发送 PUT 请求', async () => {
      sdk = new PluginSDK()

      const requestPromise = sdk.put('/api/instances/1', { name: 'updated' })

      const callArgs = mockBus.$emit.mock.calls[0][1]
      expect(callArgs.method).toBe('PUT')

      // 模拟响应
      const requestId = callArgs.requestId
      const apiResponseHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:API_RESPONSE'
      )[1]
      apiResponseHandler({ requestId, success: true, data: {} })

      await requestPromise
    })
  })

  describe('delete', () => {
    it('应该发送 DELETE 请求', async () => {
      sdk = new PluginSDK()

      const requestPromise = sdk.delete('/api/instances/1')

      const callArgs = mockBus.$emit.mock.calls[0][1]
      expect(callArgs.method).toBe('DELETE')

      // 模拟响应
      const requestId = callArgs.requestId
      const apiResponseHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:API_RESPONSE'
      )[1]
      apiResponseHandler({ requestId, success: true, data: null })

      await requestPromise
    })
  })

  describe('事件监听', () => {
    it('应该添加事件监听器', () => {
      sdk = new PluginSDK()

      const listener = vi.fn()
      sdk.on(MessageTypes.INIT, listener)

      // 模拟接收 INIT 事件
      const initHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:INIT'
      )[1]
      initHandler({ instanceId: 1 })

      expect(listener).toHaveBeenCalledWith({ instanceId: 1 })
    })

    it('应该移除事件监听器', () => {
      sdk = new PluginSDK()

      const listener = vi.fn()
      sdk.on(MessageTypes.INIT, listener)
      sdk.off(MessageTypes.INIT, listener)

      // 模拟接收 INIT 事件
      const initHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:INIT'
      )[1]
      initHandler({ instanceId: 1 })

      expect(listener).not.toHaveBeenCalled()
    })

    it('应该支持多个监听器', () => {
      sdk = new PluginSDK()

      const listener1 = vi.fn()
      const listener2 = vi.fn()

      sdk.on(MessageTypes.INIT, listener1)
      sdk.on(MessageTypes.INIT, listener2)

      // 模拟接收 INIT 事件
      const initHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:INIT'
      )[1]
      initHandler({ instanceId: 1 })

      expect(listener1).toHaveBeenCalled()
      expect(listener2).toHaveBeenCalled()
    })

    it('监听器错误不应该影响其他监听器', () => {
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

      sdk = new PluginSDK()

      const listener1 = vi.fn(() => {
        throw new Error('Listener error')
      })
      const listener2 = vi.fn()

      sdk.on(MessageTypes.INIT, listener1)
      sdk.on(MessageTypes.INIT, listener2)

      // 模拟接收 INIT 事件
      const initHandler = mockBus.$on.mock.calls.find(
        call => call[0] === 'plugin-minecraft:INIT'
      )[1]
      initHandler({ instanceId: 1 })

      expect(listener1).toHaveBeenCalled()
      expect(listener2).toHaveBeenCalled()
      expect(consoleSpy).toHaveBeenCalled()

      consoleSpy.mockRestore()
    })
  })

  describe('getPluginSDK', () => {
    it('应该返回默认实例', () => {
      const sdk1 = getPluginSDK()
      const sdk2 = getPluginSDK()

      expect(sdk1).toBe(sdk2)

      sdk1.destroy()
    })

    it('应该使用选项创建默认实例', () => {
      const sdk = getPluginSDK({ version: '2.0.0' })

      expect(sdk).toBeDefined()

      sdk.destroy()
    })
  })

  describe('createPluginSDK', () => {
    it('应该创建新实例', () => {
      const sdk1 = createPluginSDK()
      const sdk2 = createPluginSDK()

      expect(sdk1).not.toBe(sdk2)

      sdk1.destroy()
      sdk2.destroy()
    })
  })

  describe('debug 模式', () => {
    it('debug 模式应该输出日志', () => {
      const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {})

      sdk = new PluginSDK({ debug: true })

      sdk.ready()

      expect(consoleSpy).toHaveBeenCalledWith(
        '[PluginSDK]',
        'Plugin ready'
      )

      consoleSpy.mockRestore()
    })

    it('非 debug 模式不应该输出日志', () => {
      const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {})

      sdk = new PluginSDK({ debug: false })

      sdk.ready()

      expect(consoleSpy).not.toHaveBeenCalled()

      consoleSpy.mockRestore()
    })
  })
})
