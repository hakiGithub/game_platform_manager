/**
 * 插件通信消息类型定义
 * 定义主应用与插件之间的所有消息接口
 *
 * 注意：当前已切换为 Wujie 微前端架构，通信方式由 postMessage 改为：
 * - 初始化数据通过 Wujie props 下发
 * - 运行时事件通过 Wujie bus 广播
 * 本文件保留消息类型与 Payload 结构，供主应用和子应用 SDK 共用
 */

// ========== 基础类型 ==========

/**
 * 消息基础接口
 */
export interface BaseMessage<T = unknown> {
  type: string
  payload: T
  timestamp?: number
}

// ========== 主应用 → 插件 消息 ==========

/**
 * INIT - 初始化实例信息
 * 主应用在子应用加载前通过 props 注入，插件就绪后也可通过 bus 重新下发
 */
export interface InitPayload {
  instanceId: number
  instanceName: string
  gameCode: string
  hostId: number
  hostIp: string
  deployPath: string
  ports: Record<string, number>
}

export interface InitMessage extends BaseMessage<InitPayload> {
  type: 'INIT'
}

/**
 * AUTH - 认证信息
 * 主应用通过 props 注入当前用户的认证信息
 */
export interface AuthPayload {
  token: string
  user: {
    id: number
    username: string
    role: string
    permissions: string[]
  }
}

export interface AuthMessage extends BaseMessage<AuthPayload> {
  type: 'AUTH'
}

/**
 * THEME_CHANGE - 主题变化
 * 主应用主题变化时通过 bus 通知插件
 */
export interface ThemeChangePayload {
  isDark: boolean
  theme?: string
}

export interface ThemeChangeMessage extends BaseMessage<ThemeChangePayload> {
  type: 'THEME_CHANGE'
}

/**
 * CONFIRM_RESULT - 确认框结果
 * 主应用返回确认框的用户选择结果
 */
export interface ConfirmResultPayload {
  requestId: string
  confirmed: boolean
}

export interface ConfirmResultMessage extends BaseMessage<ConfirmResultPayload> {
  type: 'CONFIRM_RESULT'
}

/**
 * 主应用发送的所有消息类型
 */
export type HostToPluginMessage =
  | InitMessage
  | AuthMessage
  | ThemeChangeMessage
  | ConfirmResultMessage

// ========== 插件 → 主应用 消息 ==========

/**
 * READY - 插件就绪
 * 插件加载完成并准备好接收消息时通过 bus 发送
 */
export interface ReadyPayload {
  version?: string
  capabilities?: string[]
}

export interface ReadyMessage extends BaseMessage<ReadyPayload> {
  type: 'READY'
}

/**
 * NAVIGATE - 请求导航
 * 插件请求主应用导航到指定路径
 */
export interface NavigatePayload {
  path: string
  query?: Record<string, string>
  params?: Record<string, string>
}

export interface NavigateMessage extends BaseMessage<NavigatePayload> {
  type: 'NAVIGATE'
}

/**
 * NOTIFY - 显示通知
 * 插件请求主应用显示通知消息
 */
export type NotifyType = 'success' | 'warning' | 'error' | 'info'

export interface NotifyPayload {
  type: NotifyType
  title: string
  message: string
  duration?: number
}

export interface NotifyMessage extends BaseMessage<NotifyPayload> {
  type: 'NOTIFY'
}

/**
 * CONFIRM - 显示确认框
 * 插件请求主应用显示确认对话框
 */
export interface ConfirmPayload {
  requestId: string
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  type?: 'info' | 'warning' | 'danger'
}

export interface ConfirmMessage extends BaseMessage<ConfirmPayload> {
  type: 'CONFIRM'
}

/**
 * API_REQUEST - API 请求
 * 插件请求主应用代理发送 API 请求
 */
export interface ApiRequestPayload {
  requestId: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  url: string
  headers?: Record<string, string>
  params?: Record<string, unknown>
  data?: unknown
}

export interface ApiRequestMessage extends BaseMessage<ApiRequestPayload> {
  type: 'API_REQUEST'
}

/**
 * API_RESPONSE - API 响应
 * 主应用返回 API 请求的响应
 */
export interface ApiResponsePayload {
  requestId: string
  success: boolean
  data?: unknown
  error?: {
    code?: number
    message: string
  }
}

export interface ApiResponseMessage extends BaseMessage<ApiResponsePayload> {
  type: 'API_RESPONSE'
}

/**
 * 插件发送的所有消息类型
 */
export type PluginToHostMessage =
  | ReadyMessage
  | NavigateMessage
  | NotifyMessage
  | ConfirmMessage
  | ApiRequestMessage

/**
 * 所有消息类型
 */
export type AllMessages =
  | HostToPluginMessage
  | PluginToHostMessage
  | ApiResponseMessage

// ========== 消息类型守卫 ==========

/**
 * 判断是否为 INIT 消息
 */
export function isInitMessage(message: BaseMessage): message is InitMessage {
  return message.type === 'INIT'
}

/**
 * 判断是否为 AUTH 消息
 */
export function isAuthMessage(message: BaseMessage): message is AuthMessage {
  return message.type === 'AUTH'
}

/**
 * 判断是否为 THEME_CHANGE 消息
 */
export function isThemeChangeMessage(message: BaseMessage): message is ThemeChangeMessage {
  return message.type === 'THEME_CHANGE'
}

/**
 * 判断是否为 CONFIRM_RESULT 消息
 */
export function isConfirmResultMessage(message: BaseMessage): message is ConfirmResultMessage {
  return message.type === 'CONFIRM_RESULT'
}

/**
 * 判断是否为 READY 消息
 */
export function isReadyMessage(message: BaseMessage): message is ReadyMessage {
  return message.type === 'READY'
}

/**
 * 判断是否为 NAVIGATE 消息
 */
export function isNavigateMessage(message: BaseMessage): message is NavigateMessage {
  return message.type === 'NAVIGATE'
}

/**
 * 判断是否为 NOTIFY 消息
 */
export function isNotifyMessage(message: BaseMessage): message is NotifyMessage {
  return message.type === 'NOTIFY'
}

/**
 * 判断是否为 CONFIRM 消息
 */
export function isConfirmMessage(message: BaseMessage): message is ConfirmMessage {
  return message.type === 'CONFIRM'
}

/**
 * 判断是否为 API_REQUEST 消息
 */
export function isApiRequestMessage(message: BaseMessage): message is ApiRequestMessage {
  return message.type === 'API_REQUEST'
}

/**
 * 判断是否为 API_RESPONSE 消息
 */
export function isApiResponseMessage(message: BaseMessage): message is ApiResponseMessage {
  return message.type === 'API_RESPONSE'
}

// ========== 插件清单类型 ==========

/**
 * 插件菜单项
 */
export interface PluginMenuItem {
  id: string
  label: string
  icon?: string
  path: string
  order?: number
  /**
   * 该菜单是否要求选中实例后才渲染子应用
   * - true（默认）：必须携带 instanceId 才能进入页面，例如 RCON、地图管理
   * - false：纯资源浏览页，无需实例即可访问，例如地图中心
   * 缺省时按 true 处理，保持向后兼容
   */
  requireInstance?: boolean
  children?: PluginMenuItem[]
}

/**
 * 插件清单
 */
export interface PluginManifest {
  pluginId: string
  name: string
  version: string
  description?: string
  author?: string
  gameCode: string
  entry: string
  menus: PluginMenuItem[]
  capabilities?: string[]
  permissions?: string[]
}

// ========== 消息常量 ==========

/**
 * 消息类型常量
 */
export const MessageTypes = {
  // 主应用 → 插件
  INIT: 'INIT',
  AUTH: 'AUTH',
  THEME_CHANGE: 'THEME_CHANGE',
  CONFIRM_RESULT: 'CONFIRM_RESULT',
  API_RESPONSE: 'API_RESPONSE',

  // 插件 → 主应用
  READY: 'READY',
  NAVIGATE: 'NAVIGATE',
  NOTIFY: 'NOTIFY',
  CONFIRM: 'CONFIRM',
  API_REQUEST: 'API_REQUEST'
} as const

/**
 * 消息来源常量（已废弃）
 * 保留仅用于兼容旧版 postMessage 插件 SDK，新 Wujie 通信不再使用
 * @deprecated
 */
export const MessageSources = {
  HOST: 'host',
  PLUGIN: 'plugin'
} as const
