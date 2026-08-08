/**
 * API 类型定义
 */

// 通用响应
export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
  timestamp: number
}

// 分页响应
export interface PageResponse<T> {
  current: number
  size: number
  total: number
  pages: number
  records: T[]
}

// 服务器状态
export interface ServerStatus {
  online: boolean
  map: string
  players: number
  maxPlayers: number
  difficulty: string
  gameMode: string
  hostname: string
  version: string
  osType?: string
  serverType?: string
  reason?: string
  fps: number
  uptime: number
}

// 地图信息
export interface MapInfo {
  name: string
  displayName: string
  type: 'campaign' | 'versus' | 'survival' | 'scavenge'
  workshopId?: string
  size: number
  installed: boolean
  enabled: boolean
}

// 插件信息
export interface PluginInfo {
  name: string
  filename: string
  version: string
  author: string
  description: string
  enabled: boolean
  hasConfig: boolean
}

// 管理员信息
export interface AdminInfo {
  steamId: string
  name: string
  flags: string
  immunity: number
  addedAt: string
}

// 性能数据
export interface PerformanceData {
  timestamp: number
  cpu: number
  memory: number
  disk: number
  networkIn: number
  networkOut: number
  fps: number
  players: number
}

// RCON 命令历史
export interface RconHistory {
  id: string
  command: string
  output: string
  timestamp: number
}

// 服务器配置
export interface ServerConfig {
  hostname: string
  motd: string
  rconPassword: string
  svLan: number
  svRegion: number
  svSteamgroup: string
  svTags: string
  hiddenServer: boolean
  lobbyOnly: boolean
  customConVars: Record<string, string>
}

// 预设配置
export interface PresetConfig {
  id: string
  name: string
  description: string
  plugins: string[]
  conVars: Record<string, string>
  createdAt: string
  updatedAt: string
}

// 文件信息
export interface FileInfo {
  name: string
  path: string
  size: number
  modifiedAt: string
  isDirectory: boolean
}

// 日志条目
export interface LogEntry {
  timestamp: string
  level: 'info' | 'warn' | 'error'
  message: string
  source?: string
}

// 实例信息
export interface InstanceInfo {
  id: number
  name: string
  hostId: number
  gameId: number
  status: string
  deployType: string
  containerId?: string
  workDir: string
}

// Wujie 微前端全局类型声明
declare global {
  interface Window {
    /** 标识当前是否运行在 Wujie 微前端环境中 */
    __POWERED_BY_WUJIE__?: boolean
    /** Wujie 注入的 props / bus 对象 */
    $wujie?: {
      props: Record<string, any>
      bus: {
        $emit: (event: string, ...args: any[]) => void
        $on: (event: string, callback: (...args: any[]) => void) => void
        $off: (event: string, callback: (...args: any[]) => void) => void
        $once?: (event: string, callback: (...args: any[]) => void) => void
      }
    }
    /** Wujie 提供的挂载/卸载钩子（兼容老版本） */
    __WUJIE_MOUNT?: () => void
    __WUJIE_UNMOUNT?: () => void
  }
}

export {}
