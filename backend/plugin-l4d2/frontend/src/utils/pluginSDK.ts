/**
 * 插件 SDK 封装
 * Wujie 微前端环境下通过 window.$wujie.props / bus 与主应用通信
 * 已废弃 iframe/postMessage 方式
 */

export interface PluginSDKOptions {
  debug?: boolean
}

export interface InitPayload {
  instanceId: number
  instanceName?: string
  hostId: number
  gameId?: number
  gameCode?: string
  [key: string]: any
}

export interface AuthPayload {
  token: string
  userId?: number
  username?: string
  [key: string]: any
}

export interface ThemePayload {
  isDark: boolean
  theme?: string
}

export class PluginSDK {
  private initialized = false
  private isReady = false
  private options: PluginSDKOptions
  private instanceInfo: InitPayload | null = null
  private authInfo: AuthPayload | null = null
  private themeInfo: ThemePayload | null = null
  /** 本地注册的事件监听器 */
  private listeners = new Map<string, Array<(data: any) => void>>()
  /** 已注册到 Wujie bus 上的事件处理器 */
  private busHandlers = new Map<string, (...args: any[]) => void>()

  constructor(options: PluginSDKOptions = {}) {
    this.options = { debug: false, ...options }
  }

  /**
   * 是否运行在 Wujie 微前端环境中
   */
  private get isWujie(): boolean {
    return typeof window !== 'undefined' && Boolean(window.__POWERED_BY_WUJIE__ && window.$wujie)
  }

  /**
   * 获取 Wujie bus 实例
   */
  private get wujieBus() {
    return window.$wujie?.bus
  }

  /**
   * 初始化 SDK
   */
  init(): void {
    if (this.initialized) {
      return
    }

    if (this.isWujie) {
      this.readInitialProps()
    }

    this.initialized = true
    this.log('SDK initialized')
  }

  /**
   * 从 Wujie props 读取初始数据
   */
  private readInitialProps(): void {
    const props = window.$wujie!.props || {}

    // 主应用 PluginContainer 使用 props.instance 传递实例信息，兼容旧版 instanceInfo
    const info = props.instance || props.instanceInfo
    if (info) {
      this.instanceInfo = info
    }

    if (props.token || props.userInfo) {
      this.authInfo = {
        token: props.token || '',
        ...(props.userInfo || {})
      }
    }

    if (props.theme !== undefined) {
      this.themeInfo = this.normalizeTheme(props.theme)
    }
  }

  /**
   * 统一主题数据格式
   */
  private normalizeTheme(theme: any): ThemePayload {
    if (typeof theme === 'boolean') {
      return { isDark: theme }
    }
    if (typeof theme === 'string') {
      return { isDark: theme === 'dark', theme }
    }
    return {
      isDark: theme?.isDark === true || theme?.theme === 'dark',
      theme: theme?.theme || theme
    }
  }

  /**
   * 销毁 SDK
   */
  destroy(): void {
    if (this.isWujie) {
      this.busHandlers.forEach((handler, event) => {
        this.wujieBus?.$off(event, handler)
      })
      this.busHandlers.clear()
    }

    this.listeners.clear()
    this.initialized = false
    this.isReady = false
    this.instanceInfo = null
    this.authInfo = null
    this.themeInfo = null
    this.log('SDK destroyed')
  }

  /**
   * 通知主应用插件已就绪
   */
  ready(): void {
    if (this.isReady) {
      return
    }

    this.sendMessage('READY', {})
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
  getThemeInfo(): ThemePayload | null {
    return this.themeInfo
  }

  /**
   * 显示通知
   */
  notify(type: 'success' | 'warning' | 'error' | 'info', title: string, message: string): void {
    this.sendMessage('NOTIFY', { type, title, message })
  }

  /**
   * 显示成功通知
   */
  notifySuccess(title: string, message: string): void {
    this.notify('success', title, message)
  }

  /**
   * 显示错误通知
   */
  notifyError(title: string, message: string): void {
    this.notify('error', title, message)
  }

  /**
   * 显示警告通知
   */
  notifyWarning(title: string, message: string): void {
    this.notify('warning', title, message)
  }

  /**
   * 显示信息通知
   */
  notifyInfo(title: string, message: string): void {
    this.notify('info', title, message)
  }

  /**
   * 显示确认框
   */
  confirm(title: string, message: string): Promise<boolean> {
    return new Promise((resolve) => {
      const requestId = `confirm_${Date.now()}`

      // 监听确认结果
      const handler = (data: any) => {
        if (data?.requestId === requestId) {
          resolve(Boolean(data.confirmed))
          this.off('CONFIRM_RESULT', handler)
        }
      }

      this.on('CONFIRM_RESULT', handler)
      this.sendMessage('CONFIRM', { requestId, title, message })
    })
  }

  /**
   * 导航到指定路径
   */
  navigate(path: string): void {
    this.sendMessage('NAVIGATE', { path })
  }

  /**
   * 添加事件监听器
   */
  on(event: string, listener: (data: any) => void): void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, [])
    }
    this.listeners.get(event)!.push(listener)

    // Wujie 环境下自动订阅 bus 对应事件
    if (this.isWujie && !this.busHandlers.has(event)) {
      const busHandler = (...args: any[]) => {
        const payload = args.length > 1 ? args : args[0]
        this.handleBusEvent(event, payload)
      }
      this.busHandlers.set(event, busHandler)
      this.wujieBus?.$on(event, busHandler)
    }
  }

  /**
   * 移除事件监听器
   */
  off(event: string, listener: (data: any) => void): void {
    const listeners = this.listeners.get(event)
    if (!listeners) {
      return
    }

    const index = listeners.indexOf(listener)
    if (index > -1) {
      listeners.splice(index, 1)
    }

    // 如果该事件已无本地监听器，则取消 bus 订阅
    if (listeners.length === 0 && this.isWujie) {
      const busHandler = this.busHandlers.get(event)
      if (busHandler) {
        this.wujieBus?.$off(event, busHandler)
        this.busHandlers.delete(event)
      }
    }
  }

  /**
   * 处理 Wujie bus 事件
   */
  private handleBusEvent(event: string, payload: any): void {
    this.log('Received bus event:', event, payload)

    // 更新内部缓存
    this.updateInternalState(event, payload)

    // 触发本地事件监听器
    const listeners = this.listeners.get(event)
    if (listeners) {
      listeners.forEach(listener => {
        try {
          listener(payload)
        } catch (error) {
          console.error('[PluginSDK] Listener error:', error)
        }
      })
    }
  }

  /**
   * 根据事件类型更新内部缓存
   */
  private updateInternalState(event: string, payload: any): void {
    switch (event) {
      case 'INIT':
      case 'INSTANCE_CHANGE':
        this.instanceInfo = payload
        break
      case 'AUTH':
      case 'AUTH_CHANGE':
        this.authInfo = payload
        break
      case 'THEME_CHANGE':
        this.themeInfo = this.normalizeTheme(payload)
        break
    }
  }

  /**
   * 发送消息到主应用
   */
  private sendMessage(type: string, payload: any): void {
    if (this.isWujie) {
      this.wujieBus?.$emit(type, payload)
    } else {
      // dev 模式下没有宿主，仅做日志记录
      this.log('[Dev] sendMessage:', type, payload)
    }
    this.log('Sent message:', type, payload)
  }

  /**
   * 日志输出
   */
  private log(...args: any[]): void {
    if (this.options.debug) {
      console.log('[PluginSDK]', ...args)
    }
  }
}

// 创建默认实例
let defaultSDK: PluginSDK | null = null

export function getPluginSDK(options?: PluginSDKOptions): PluginSDK {
  if (!defaultSDK) {
    defaultSDK = new PluginSDK(options)
  }
  return defaultSDK
}

export function createPluginSDK(options?: PluginSDKOptions): PluginSDK {
  return new PluginSDK(options)
}

export default PluginSDK
