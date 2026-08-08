/**
 * 插件通信管理器（Wujie 版）
 * 主应用端的插件通信管理
 *
 * 通信方式：
 * - 初始化数据：通过 Wujie 的 props 注入
 * - 运行时通信：通过 Wujie 全局 bus 进行事件广播
 * 不再使用 iframe postMessage
 */

import { ref, onBeforeUnmount, getCurrentInstance, type Ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import WujieVue from 'wujie-vue3'
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
  type PluginManifest,
  MessageTypes
} from '../types/messageTypes'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import request from '@/utils/request'

// Wujie 全局事件总线
const { bus } = WujieVue

/**
 * 确认请求映射
 * 用于存储等待用户响应的确认请求
 */
interface ConfirmRequest {
  resolve: (confirmed: boolean) => void
  timer?: ReturnType<typeof setTimeout>
}

/**
 * API 请求映射
 * 用于存储等待响应的 API 请求
 */
interface ApiRequest {
  resolve: (response: ApiResponsePayload) => void
  reject: (error: Error) => void
  timer?: ReturnType<typeof setTimeout>
}

/**
 * 插件通信管理器选项
 */
export interface PluginCommunicationOptions {
  /** Wujie 子应用名称 */
  name: string
  /** 插件清单 */
  manifest: Ref<PluginManifest | null>
  /** 实例信息 */
  instanceInfo: Ref<InitPayload | null>
  /** 插件就绪回调 */
  onReady?: (payload: ReadyPayload) => void
  /** 消息处理回调 */
  onMessage?: (message: BaseMessage) => void
}

/**
 * 创建插件通信管理器
 */
export function usePluginCommunication(options: PluginCommunicationOptions) {
  const { name, manifest, instanceInfo, onReady, onMessage } = options

  const router = useRouter()
  const userStore = useUserStore()
  const appStore = useAppStore()

  // 插件就绪状态
  const isReady = ref(false)

  // 确认请求映射
  const confirmRequests = new Map<string, ConfirmRequest>()

  // API 请求映射
  const apiRequests = new Map<string, ApiRequest>()

  // 请求 ID 计数器
  let requestIdCounter = 0

  /**
   * 生成唯一请求 ID
   */
  function generateRequestId(): string {
    return `req_${Date.now()}_${++requestIdCounter}`
  }

  /**
   * 发送消息到指定子应用
   * 通过 Wujie 全局 bus 以 "name:type" 为事件名广播
   */
  function sendMessage<T>(type: string, payload: T): void {
    const eventName = `${name}:${type}`
    bus.$emit(eventName, payload)

    const message: BaseMessage<T> = {
      type,
      payload,
      timestamp: Date.now()
    }

    if (onMessage) {
      onMessage(message)
    }
  }

  /**
   * 发送初始化消息
   * 子应用已可通过 props 获取初始数据，此方法用于热更新场景
   */
  function sendInit(): void {
    if (!instanceInfo.value) {
      console.warn('[PluginCommunication] instance info not ready')
      return
    }

    sendMessage<InitPayload>(MessageTypes.INIT, instanceInfo.value)
  }

  /**
   * 发送认证消息
   */
  function sendAuth(): void {
    const payload: AuthPayload = {
      token: userStore.token,
      user: {
        id: userStore.userInfo?.id || 0,
        username: userStore.username,
        role: userStore.userInfo?.role || '',
        permissions: userStore.permissions || []
      }
    }

    sendMessage<AuthPayload>(MessageTypes.AUTH, payload)
  }

  /**
   * 发送主题变化消息
   */
  function sendThemeChange(): void {
    const payload: ThemeChangePayload = {
      isDark: appStore.theme === 'dark',
      theme: appStore.theme
    }

    sendMessage<ThemeChangePayload>(MessageTypes.THEME_CHANGE, payload)
  }

  /**
   * 发送确认结果
   */
  function sendConfirmResult(requestId: string, confirmed: boolean): void {
    const payload: ConfirmResultPayload = {
      requestId,
      confirmed
    }

    sendMessage<ConfirmResultPayload>(MessageTypes.CONFIRM_RESULT, payload)
  }

  /**
   * 发送 API 响应
   */
  function sendApiResponse(requestId: string, response: ApiResponsePayload): void {
    sendMessage<ApiResponsePayload>(MessageTypes.API_RESPONSE, {
      ...response,
      requestId
    })
  }

  /**
   * 处理插件就绪事件
   */
  function handleReady(payload: ReadyPayload): void {
    isReady.value = true
    console.log('[PluginCommunication] Plugin ready:', payload)

    // 插件就绪后，通过 bus 补充下发一次认证与主题信息
    sendAuth()
    sendThemeChange()

    // 调用就绪回调
    if (onReady) {
      onReady(payload)
    }
  }

  /**
   * 处理导航请求
   */
  function handleNavigate(payload: NavigatePayload): void {
    console.log('[PluginCommunication] Navigate request:', payload)

    router.push({
      path: payload.path,
      query: payload.query
    })
  }

  /**
   * 处理通知请求
   */
  function handleNotify(payload: NotifyPayload): void {
    console.log('[PluginCommunication] Notify request:', payload)

    ElMessage({
      type: payload.type,
      message: payload.title + (payload.message ? `: ${payload.message}` : ''),
      duration: payload.duration || 3000
    })
  }

  /**
   * 处理确认框请求
   */
  async function handleConfirm(payload: ConfirmPayload): Promise<void> {
    console.log('[PluginCommunication] Confirm request:', payload)

    try {
      await ElMessageBox.confirm(
        payload.message,
        payload.title,
        {
          confirmButtonText: payload.confirmText || '确定',
          cancelButtonText: payload.cancelText || '取消',
          type: payload.type || 'info'
        }
      )

      sendConfirmResult(payload.requestId, true)
    } catch {
      sendConfirmResult(payload.requestId, false)
    }
  }

  /**
   * 处理 API 请求
   * 代理子应用发送的 HTTP 请求，自动携带主应用 token 和 baseURL
   */
  async function handleApiRequest(payload: ApiRequestPayload): Promise<void> {
    console.log('[PluginCommunication] API request:', payload)

    try {
      const response = await request({
        method: payload.method,
        url: payload.url,
        headers: payload.headers,
        params: payload.params,
        data: payload.data
      })

      sendApiResponse(payload.requestId, {
        requestId: payload.requestId,
        success: true,
        data: response
      })
    } catch (error: unknown) {
      const err = error as { response?: { status?: number; data?: { message?: string } }; message?: string }
      sendApiResponse(payload.requestId, {
        requestId: payload.requestId,
        success: false,
        error: {
          code: err.response?.status,
          message: err.response?.data?.message || err.message || '请求失败'
        }
      })
    }
  }

  /**
   * 获取 bus 事件名
   */
  function getEventName(type: string): string {
    return `${name}:${type}`
  }

  /**
   * 初始化通信
   */
  function init(): void {
    bus.$on(getEventName(MessageTypes.READY), handleReady)
    bus.$on(getEventName(MessageTypes.NAVIGATE), handleNavigate)
    bus.$on(getEventName(MessageTypes.NOTIFY), handleNotify)
    bus.$on(getEventName(MessageTypes.CONFIRM), handleConfirm)
    bus.$on(getEventName(MessageTypes.API_REQUEST), handleApiRequest)
  }

  /**
   * 销毁通信
   */
  function destroy(): void {
    bus.$off(getEventName(MessageTypes.READY), handleReady)
    bus.$off(getEventName(MessageTypes.NAVIGATE), handleNavigate)
    bus.$off(getEventName(MessageTypes.NOTIFY), handleNotify)
    bus.$off(getEventName(MessageTypes.CONFIRM), handleConfirm)
    bus.$off(getEventName(MessageTypes.API_REQUEST), handleApiRequest)

    // 清理所有待处理的确认请求
    confirmRequests.forEach((request) => {
      if (request.timer) {
        clearTimeout(request.timer)
      }
    })
    confirmRequests.clear()

    // 清理所有待处理的 API 请求
    apiRequests.forEach((request) => {
      if (request.timer) {
        clearTimeout(request.timer)
      }
      request.reject(new Error('Communication destroyed'))
    })
    apiRequests.clear()

    isReady.value = false
  }

  /**
   * 重新初始化插件
   */
  function reinit(): void {
    isReady.value = false
    // 等待插件重新发送 READY 事件
  }

  // 立即初始化，注册 bus 监听
  init()

  // 在组件 setup 中使用 onBeforeUnmount 自动清理
  // 在测试环境或非组件场景中直接调用时，通过 destroy() 手动清理
  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      destroy()
    })
  }

  return {
    // 状态
    isReady,

    // 发送消息方法
    sendMessage,
    sendInit,
    sendAuth,
    sendThemeChange,
    sendConfirmResult,
    sendApiResponse,

    // 生命周期方法
    init,
    destroy,
    reinit
  }
}

/**
 * 插件清单 API
 */
export async function fetchPluginManifest(gameCode: string): Promise<PluginManifest> {
  const response = await request({
    url: `/pf4j/plugin/${gameCode}/manifest`,
    method: 'get',
    // 插件未安装/未注册属于预期分支，交由调用方降级处理，不弹全局错误通知
    silent: true
  })

  // 将后端响应格式映射为前端 PluginManifest 接口
  const data = response as any
  const backendMenus: any[] = data.frontend?.menus || []
  const mappedMenus = mapBackendMenusToFrontend(backendMenus)

  return {
    pluginId: data.pluginId || '',
    name: data.gameName || data.name || '',
    version: data.version || '',
    description: data.description || '',
    gameCode: data.gameCode || gameCode,
    entry: data.frontend?.entry || data.frontendEntry || '',
    menus: mappedMenus,
    // ADR-0001: capabilities 从菜单 path 集合推导（后端 extension.getManifest() 返回的 Map
    // 中不再放 features，改为放 capabilities key，值为菜单 path 列表）
    capabilities: data.extensions?.capabilities
              || data.frontend?.menus?.map((m: any) => m.path)
              || [],
    permissions: []
  }
}

/**
 * 将后端 MenuConfig[] 转换为前端 PluginMenuItem[]
 * 后端字段: title, path, icon, order, parent, requireInstance
 * 前端字段: id, label, path, icon, order, requireInstance, children
 *
 * requireInstance 说明：
 * - 后端未显式提供时默认视为 true，保持原有"菜单需要选中实例"的兼容行为
 * - 后端显式 false（如地图中心）时，前端跳过实例选择直接渲染子应用
 */
function mapBackendMenusToFrontend(backendMenus: any[]): any[] {
  if (!backendMenus || backendMenus.length === 0) return []

  const menuMap = new Map<string, any>()
  const roots: any[] = []

  // 第一遍：创建所有菜单项
  for (const item of backendMenus) {
    const id = item.path?.replace(/^\/+|\/+$/g, '').replace(/\//g, '-') || item.title || 'unknown'
    const menuItem: any = {
      id,
      label: item.title || item.label || '',
      path: item.path || '/',
      icon: item.icon,
      order: item.order,
      // 后端未提供 requireInstance 时默认 true，保持向后兼容
      requireInstance: item.requireInstance !== false
    }
    if (item.children && item.children.length > 0) {
      menuItem.children = []
    }
    menuMap.set(item.path, menuItem)
  }

  // 第二遍：根据 parent 建立层级关系
  for (const item of backendMenus) {
    const menuItem = menuMap.get(item.path)
    if (!menuItem) continue

    if (item.parent && menuMap.has(item.parent)) {
      const parent = menuMap.get(item.parent)!
      if (!parent.children) parent.children = []
      parent.children.push(menuItem)
    } else if (!item.parent) {
      roots.push(menuItem)
    }
  }

  // 如果所有项都有 parent 或者都无 parent，返回所有项
  return roots.length > 0 ? roots : Array.from(menuMap.values())
}
