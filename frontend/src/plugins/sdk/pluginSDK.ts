/**
 * 插件通信 SDK（Wujie 版）
 * 供插件前端使用的通信工具库
 *
 * 使用方式：
 * 1. 在插件入口文件中初始化 SDK
 * 2. 调用 ready() 方法通知主应用插件已就绪
 * 3. 使用其他方法与主应用通信
 *
 * 通信方式：
 * - 初始化数据通过 window.$wujie.props 读取
 * - 运行时事件通过 window.$wujie.bus 广播
 */

import {
  type BaseMessage,
  type InitPayload,
  type AuthPayload,
  type ThemeChangePayload,
  type ConfirmResultPayload,
  type ReadyPayload,
  type NavigatePayload,
  type NotifyPayload,
  type ConfirmPayload,
  type ApiRequestPayload,
  type ApiResponsePayload,
  MessageTypes
} from '../types/messageTypes'

/**
 * Wujie 注入的子应用上下文
 */
interface WujieContext {
  props: Record<string, unknown>
  bus: {
    $on: (event: string, callback: (payload: unknown) => void) => void
    $off: (event: string, callback: (payload: unknown) => void) => void
    $emit: (event: string, payload: unknown) => void
  }
}

/**
 * 获取 Wujie 上下文
 */
function getWujieContext(): WujieContext | null {
  const wujie = (window as unknown as { $wujie?: WujieContext }).$wujie
  if (!wujie) {
    console.warn('[PluginSDK] Wujie context not found, make sure running inside Wujie sandbox')
    return null
  }
  return wujie
}

/**
 * SDK 配置选项
 */
export interface PluginSDKOptions {
  /** 插件版本 */
  version?: string
  /** 插件能力列表 */
  capabilities?: string[]
  /** 是否自动初始化 */
  autoInit?: boolean
  /** 调试模式 */
  debug?: boolean
}

/**
 * 确认请求
 */
interface ConfirmRequest {
  resolve: (confirmed: boolean) => void
  reject: (error: Error) => void
  timer?: ReturnType<typeof setTimeout>
}

/**
 * API 请求
 */
interface ApiRequest {
  resolve: (response: ApiResponsePayload) => void
  reject: (error: Error) => void
  timer?: ReturnType<typeof setTimeout>
}

/**
 * 事件监听器
 */
type EventListener = (data: unknown) => void

/**
 * 插件 SDK 类
 */
export class PluginSDK {
  private options: PluginSDKOptions
  private initialized = false
  private isReady = false

  // 存储的主应用数据
  private instanceInfo: InitPayload | null = null
  private authInfo: AuthPayload | null = null
  private themeInfo: ThemeChangePayload | null = null

  // 请求映射
  private confirmRequests = new Map<string, ConfirmRequest>()
  private apiRequests = new Map<string, ApiRequest>()
  private requestIdCounter = 0

  // 事件监听器
  private eventListeners = new Map<string, EventListener[]>()

  // 超时配置
  private confirmTimeout = 30000 // 确认框超时时间
  private apiTimeout = 60000 // API 请求超时时间

  constructor(options: PluginSDKOptions = {}) {
    this.options = {
      autoInit: true,
      debug: false,
      ...options
    }

    if (this.options.autoInit) {
      this.init()
    }
  }

  /**
   * 初始化 SDK
   * 从 Wujie props 读取初始数据，并注册 bus 监听
   */
  init(): void {
    if (this.initialized) {
      console.warn('[PluginSDK] Already initialized')
      return
    }

    const wujie = getWujieContext()
    if (!wujie) {
      console.warn('[PluginSDK] Wujie context not available, SDK will not receive events')
      return
    }

    // 读取初始 props
    const props = wujie.props || {}
    if (props.instance) {
      this.instanceInfo = props.instance as InitPayload
    }
    if (props.auth) {
      this.authInfo = props.auth as AuthPayload
    }
    if (props.theme) {
      this.themeInfo = props.theme as ThemeChangePayload
    }

    // 监听主应用 bus 事件
    const appName = this.getAppName()
    wujie.bus.$on(`${appName}:${MessageTypes.INIT}`, this.handleInit.bind(this))
    wujie.bus.$on(`${appName}:${MessageTypes.AUTH}`, this.handleAuth.bind(this))
    wujie.bus.$on(`${appName}:${MessageTypes.THEME_CHANGE}`, this.handleThemeChange.bind(this))
    wujie.bus.$on(`${appName}:${MessageTypes.CONFIRM_RESULT}`, this.handleConfirmResult.bind(this))
    wujie.bus.$on(`${appName}:${MessageTypes.API_RESPONSE}`, this.handleApiResponse.bind(this))

    this.initialized = true
    this.log('SDK initialized')
  }

  /**
   * 销毁 SDK
   */
  destroy(): void {
    const wujie = getWujieContext()
    if (wujie) {
      const appName = this.getAppName()
      wujie.bus.$off(`${appName}:${MessageTypes.INIT}`, this.handleInit.bind(this))
      wujie.bus.$off(`${appName}:${MessageTypes.AUTH}`, this.handleAuth.bind(this))
      wujie.bus.$off(`${appName}:${MessageTypes.THEME_CHANGE}`, this.handleThemeChange.bind(this))
      wujie.bus.$off(`${appName}:${MessageTypes.CONFIRM_RESULT}`, this.handleConfirmResult.bind(this))
      wujie.bus.$off(`${appName}:${MessageTypes.API_RESPONSE}`, this.handleApiResponse.bind(this))
    }

    // 清理所有待处理的请求
    this.confirmRequests.forEach(request => {
      if (request.timer) clearTimeout(request.timer)
      request.reject(new Error('SDK destroyed'))
    })
    this.confirmRequests.clear()

    this.apiRequests.forEach(request => {
      if (request.timer) clearTimeout(request.timer)
      request.reject(new Error('SDK destroyed'))
    })
    this.apiRequests.clear()

    this.eventListeners.clear()
    this.initialized = false
    this.isReady = false

    this.log('SDK destroyed')
  }

  /**
   * 通知主应用插件已就绪
   */
  ready(): void {
    if (this.isReady) {
      console.warn('[PluginSDK] Already ready')
      return
    }

    const payload: ReadyPayload = {
      version: this.options.version,
      capabilities: this.options.capabilities
    }

    this.sendMessage(MessageTypes.READY, payload)
    this.isReady = true
    this.log('Plugin ready')
  }

  /**
   * 获取实例信息
   */
  getInstanceInfo(): InitPayload | null {
    return this.instanceInfo
  }

  /**
   * 获取认证信息
   */
  getAuthInfo(): AuthPayload | null {
    return this.authInfo
  }

  /**
   * 获取主题信息
   */
  getThemeInfo(): ThemeChangePayload | null {
    return this.themeInfo
  }

  /**
   * 请求导航
   */
  navigate(path: string, query?: Record<string, string>): void {
    const payload: NavigatePayload = { path, query }
    this.sendMessage(MessageTypes.NAVIGATE, payload)
  }

  /**
   * 显示通知
   */
  notify(type: NotifyPayload['type'], title: string, message: string, duration?: number): void {
    const payload: NotifyPayload = { type, title, message, duration }
    this.sendMessage(MessageTypes.NOTIFY, payload)
  }

  /**
   * 显示成功通知
   */
  notifySuccess(title: string, message: string, duration?: number): void {
    this.notify('success', title, message, duration)
  }

  /**
   * 显示警告通知
   */
  notifyWarning(title: string, message: string, duration?: number): void {
    this.notify('warning', title, message, duration)
  }

  /**
   * 显示错误通知
   */
  notifyError(title: string, message: string, duration?: number): void {
    this.notify('error', title, message, duration)
  }

  /**
   * 显示信息通知
   */
  notifyInfo(title: string, message: string, duration?: number): void {
    this.notify('info', title, message, duration)
  }

  /**
   * 显示确认框
   */
  confirm(
    title: string,
    message: string,
    options?: {
      confirmText?: string
      cancelText?: string
      type?: 'info' | 'warning' | 'danger'
    }
  ): Promise<boolean> {
    return new Promise((resolve, reject) => {
      const requestId = this.generateRequestId()

      const payload: ConfirmPayload = {
        requestId,
        title,
        message,
        confirmText: options?.confirmText,
        cancelText: options?.cancelText,
        type: options?.type
      }

      // 设置超时
      const timer = setTimeout(() => {
        this.confirmRequests.delete(requestId)
        reject(new Error('Confirm timeout'))
      }, this.confirmTimeout)

      this.confirmRequests.set(requestId, { resolve, reject, timer })
      this.sendMessage(MessageTypes.CONFIRM, payload)
    })
  }

  /**
   * 发送 API 请求
   */
  request<T = unknown>(
    method: ApiRequestPayload['method'],
    url: string,
    options?: {
      headers?: Record<string, string>
      params?: Record<string, unknown>
      data?: unknown
    }
  ): Promise<T> {
    return new Promise((resolve, reject) => {
      const requestId = this.generateRequestId()

      const payload: ApiRequestPayload = {
        requestId,
        method,
        url,
        headers: options?.headers,
        params: options?.params,
        data: options?.data
      }

      // 设置超时
      const timer = setTimeout(() => {
        this.apiRequests.delete(requestId)
        reject(new Error('API request timeout'))
      }, this.apiTimeout)

      this.apiRequests.set(requestId, {
        resolve: (response: ApiResponsePayload) => {
          if (response.success) {
            resolve(response.data as T)
          } else {
            reject(new Error(response.error?.message || 'Request failed'))
          }
        },
        reject,
        timer
      })

      this.sendMessage(MessageTypes.API_REQUEST, payload)
    })
  }

  /**
   * GET 请求
   */
  get<T = unknown>(url: string, params?: Record<string, unknown>): Promise<T> {
    return this.request<T>('GET', url, { params })
  }

  /**
   * POST 请求
   */
  post<T = unknown>(url: string, data?: unknown): Promise<T> {
    return this.request<T>('POST', url, { data })
  }

  /**
   * PUT 请求
   */
  put<T = unknown>(url: string, data?: unknown): Promise<T> {
    return this.request<T>('PUT', url, { data })
  }

  /**
   * DELETE 请求
   */
  delete<T = unknown>(url: string): Promise<T> {
    return this.request<T>('DELETE', url)
  }

  /**
   * 添加事件监听器
   */
  on(event: string, listener: EventListener): void {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, [])
    }
    this.eventListeners.get(event)!.push(listener)
  }

  /**
   * 移除事件监听器
   */
  off(event: string, listener: EventListener): void {
    const listeners = this.eventListeners.get(event)
    if (listeners) {
      const index = listeners.indexOf(listener)
      if (index > -1) {
        listeners.splice(index, 1)
      }
    }
  }

  /**
   * 获取当前子应用名称
   * 用于构造 bus 事件名
   */
  private getAppName(): string {
    const wujie = getWujieContext()
    // Wujie 未注入时退化为默认值，便于单元测试
    if (!wujie) return 'plugin-unknown'

    // 从 props 中的 gameCode 推断，或从 URL 路径推断
    const gameCode = (wujie.props?.instance as InitPayload | undefined)?.gameCode
    if (gameCode) return `plugin-${gameCode}`

    // 兜底：根据当前 location 路径推断
    const pathMatch = window.location.pathname.match(/\/plugin\/([^/]+)/)
    if (pathMatch) return `plugin-${pathMatch[1]}`

    return 'plugin-unknown'
  }

  /**
   * 处理初始化消息
   */
  private handleInit(payload: InitPayload): void {
    this.instanceInfo = payload
    this.emit(MessageTypes.INIT, payload)
    this.log('Instance info received:', payload)
  }

  /**
   * 处理认证消息
   */
  private handleAuth(payload: AuthPayload): void {
    this.authInfo = payload
    this.emit(MessageTypes.AUTH, payload)
    this.log('Auth info received:', payload)
  }

  /**
   * 处理主题变化消息
   */
  private handleThemeChange(payload: ThemeChangePayload): void {
    this.themeInfo = payload
    this.emit(MessageTypes.THEME_CHANGE, payload)
    this.log('Theme changed:', payload)
  }

  /**
   * 处理确认结果消息
   */
  private handleConfirmResult(payload: ConfirmResultPayload): void {
    const request = this.confirmRequests.get(payload.requestId)
    if (request) {
      if (request.timer) clearTimeout(request.timer)
      this.confirmRequests.delete(payload.requestId)
      request.resolve(payload.confirmed)
    }
  }

  /**
   * 处理 API 响应消息
   */
  private handleApiResponse(payload: ApiResponsePayload): void {
    const request = this.apiRequests.get(payload.requestId)
    if (request) {
      if (request.timer) clearTimeout(request.timer)
      this.apiRequests.delete(payload.requestId)
      request.resolve(payload)
    }
  }

  /**
   * 发送消息到主应用
   */
  private sendMessage<T>(type: string, payload: T): void {
    const wujie = getWujieContext()
    if (!wujie) {
      console.warn('[PluginSDK] Cannot send message, Wujie context not available')
      return
    }

    const appName = this.getAppName()
    wujie.bus.$emit(`${appName}:${type}`, payload)

    this.log('Sent message:', { type, payload })
  }

  /**
   * 触发事件
   */
  private emit(event: string, data: unknown): void {
    const listeners = this.eventListeners.get(event)
    if (listeners) {
      listeners.forEach(listener => {
        try {
          listener(data)
        } catch (error) {
          console.error('[PluginSDK] Event listener error:', error)
        }
      })
    }
  }

  /**
   * 生成请求 ID
   */
  private generateRequestId(): string {
    return `req_${Date.now()}_${++this.requestIdCounter}`
  }

  /**
   * 日志输出
   */
  private log(...args: unknown[]): void {
    if (this.options.debug) {
      console.log('[PluginSDK]', ...args)
    }
  }
}

// 创建默认实例
let defaultSDK: PluginSDK | null = null

/**
 * 获取默认 SDK 实例
 */
export function getPluginSDK(options?: PluginSDKOptions): PluginSDK {
  if (!defaultSDK) {
    defaultSDK = new PluginSDK(options)
  }
  return defaultSDK
}

/**
 * 创建新的 SDK 实例
 */
export function createPluginSDK(options?: PluginSDKOptions): PluginSDK {
  return new PluginSDK(options)
}

// 默认导出
export default PluginSDK
