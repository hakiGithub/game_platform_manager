/**
 * 插件状态管理
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { PluginSDK, getPluginSDK } from '@/utils/pluginSDK'
import type { InitPayload, AuthPayload, ThemePayload } from '@/utils/pluginSDK'

export const usePluginStore = defineStore('plugin', () => {
  const sdk = ref<PluginSDK | null>(null)
  const instanceInfo = ref<InitPayload | null>(null)
  const authInfo = ref<AuthPayload | null>(null)
  const themeInfo = ref<ThemePayload | null>(null)
  const isReady = ref(false)
  const isWujie = ref(false)

  /**
   * 初始化 SDK
   */
  function initSDK() {
    if (sdk.value) {
      return
    }

    const pluginSDK = getPluginSDK({ debug: true })

    // 监听初始化消息（兼容老版本 INIT）
    pluginSDK.on('INIT', (payload: InitPayload) => {
      instanceInfo.value = payload
    })

    // 监听实例变化消息
    pluginSDK.on('INSTANCE_CHANGE', (payload: InitPayload) => {
      instanceInfo.value = payload
    })

    // 监听认证消息（兼容老版本 AUTH）
    pluginSDK.on('AUTH', (payload: AuthPayload) => {
      authInfo.value = payload
    })

    // 监听认证信息变化
    pluginSDK.on('AUTH_CHANGE', (payload: AuthPayload) => {
      authInfo.value = payload
    })

    // 监听主题变化
    pluginSDK.on('THEME_CHANGE', (payload: ThemePayload) => {
      themeInfo.value = payload
    })

    pluginSDK.init()
    sdk.value = pluginSDK
  }

  /**
   * 销毁 SDK
   */
  function destroySDK() {
    if (sdk.value) {
      sdk.value.destroy()
      sdk.value = null
    }
    isReady.value = false
  }

  /**
   * 设置实例信息
   */
  function setInstanceInfo(payload: InitPayload | null) {
    instanceInfo.value = payload
  }

  /**
   * 清除当前实例选择（实例不存在时调用）
   */
  function clearInstance() {
    instanceInfo.value = null
  }

  /**
   * 设置认证信息
   */
  function setAuthInfo(payload: AuthPayload | null) {
    authInfo.value = payload
  }

  /**
   * 设置主题信息
   */
  function setTheme(payload: ThemePayload | null) {
    themeInfo.value = payload
  }

  /**
   * 从 Wujie props 同步初始化状态
   * 兼容主应用 PluginContainer 传递的字段名：
   *   - 实例信息：props.instance（主应用实际字段名），兼容旧版 props.instanceInfo
   *   - 认证信息：props.auth = { token, user }（主应用实际结构），兼容旧版 props.token / props.userInfo
   *   - 主题信息：props.theme
   */
  function syncFromWujieProps() {
    const wujie = (window as any).$wujie
    const isWujieEnv = Boolean(window.__POWERED_BY_WUJIE__) || wujie?.props?.mode === 'wujie'
    if (!isWujieEnv || !wujie?.props) {
      return
    }

    const props = wujie.props
    // 明确标记当前处于 Wujie 环境，供布局组件可靠读取
    isWujie.value = true

    // 实例信息：优先取主应用实际传递的 "instance" 字段，兼容旧版 "instanceInfo"
    const info = props.instance || props.instanceInfo
    if (info) {
      setInstanceInfo(info)
    }

    // 认证信息：优先取主应用实际传递的 "auth" 对象（含 token 和 user），兼容旧版顶层 token/userInfo
    const auth = props.auth
    if (auth) {
      const user = auth.user || auth.userInfo || {}
      setAuthInfo({
        token: auth.token || '',
        userId: user.id,
        username: user.username,
        role: user.role,
        permissions: user.permissions,
        ...user
      })
    } else if (props.token || props.userInfo) {
      setAuthInfo({
        token: props.token || '',
        ...(props.userInfo || {})
      })
    }

    // 主题信息
    const theme = props.theme
    if (theme !== undefined) {
      const isDark = typeof theme === 'boolean'
        ? theme
        : theme === 'dark' || theme?.isDark === true
      setTheme({
        isDark,
        theme: typeof theme === 'string' ? theme : theme?.theme
      })
    }
  }

  /**
   * 通知主应用插件已就绪
   */
  function ready() {
    if (sdk.value && !isReady.value) {
      sdk.value.ready()
      isReady.value = true
    }
  }

  /**
   * 显示通知
   */
  function notify(type: 'success' | 'warning' | 'error' | 'info', title: string, message: string) {
    if (sdk.value) {
      sdk.value.notify(type, title, message)
    }
  }

  /**
   * 显示成功通知
   */
  function notifySuccess(title: string, message: string) {
    notify('success', title, message)
  }

  /**
   * 显示错误通知
   */
  function notifyError(title: string, message: string) {
    notify('error', title, message)
  }

  /**
   * 显示警告通知
   */
  function notifyWarning(title: string, message: string) {
    notify('warning', title, message)
  }

  /**
   * 显示信息通知
   */
  function notifyInfo(title: string, message: string) {
    notify('info', title, message)
  }

  /**
   * 显示确认框
   */
  function confirm(title: string, message: string): Promise<boolean> {
    if (sdk.value) {
      return sdk.value.confirm(title, message)
    }
    return Promise.resolve(false)
  }

  /**
   * 导航到指定路径
   */
  function navigate(path: string) {
    if (sdk.value) {
      sdk.value.navigate(path)
    }
  }

  return {
    sdk,
    instanceInfo,
    authInfo,
    themeInfo,
    isReady,
    isWujie,
    initSDK,
    destroySDK,
    setInstanceInfo,
    setAuthInfo,
    setTheme,
    syncFromWujieProps,
    clearInstance,
    ready,
    notify,
    notifySuccess,
    notifyError,
    notifyWarning,
    notifyInfo,
    confirm,
    navigate
  }
})
