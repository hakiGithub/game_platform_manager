/**
 * L4D2 API 接口
 */
import { get, post, put, del, upload } from './request'
import type {
  PluginInfo,
  AdminInfo,
  PerformanceData,
  ServerConfig,
  PresetConfig,
  FileInfo
} from '@/types'

/**
 * 服务器状态 API
 */
export const serverApi = {
  // 获取服务器状态（正确端点：POST /rcon/status，需传入 instanceId）
  getStatus: (instanceId: number) =>
    post<any>('/rcon/status', { instanceId }).then((vo) => ({
      online: Boolean(vo?.online),
      map: vo?.map || '',
      players: vo?.currentPlayers || 0,
      maxPlayers: vo?.maxPlayers || 0,
      difficulty: vo?.difficulty || 'normal',
      gameMode: vo?.gameMode || 'coop',
      hostname: vo?.hostname || '',
      version: vo?.version || '',
      osType: vo?.osType || '',
      serverType: vo?.serverType || '',
      reason: vo?.reason || '',
      fps: 0,
      uptime: 0
    })),
  
  // 启动服务器
  start: () => post<void>('/server/start'),
  
  // 停止服务器
  stop: () => post<void>('/server/stop'),
  
  // 重启服务器
  restart: () => post<void>('/server/restart'),
  
  // 更新服务器
  update: () => post<void>('/server/update'),
  
  // 获取服务器配置
  getConfig: () => get<ServerConfig>('/server/config'),
  
  // 更新服务器配置
  updateConfig: (config: Partial<ServerConfig>) => put<void>('/server/config', config),
  
  // 获取服务器信息
  getInfo: () => get<{
    hostname: string
    motd: string
    version: string
    address: string
    port: number
  }>('/server/info')
}

/**
 * 实例状态与生命周期 API
 * 对应后端 InstanceController，提供实例运行状态查询与启动/停止/重启。
 * 通过 InstanceQueryService SPI 调用宿主能力，Wujie 模式下行为与主应用一致。
 */
export interface InstanceStatusVO {
  instanceId: number
  instanceName: string
  hostId: number
  hostIp: string
  gameCode: string
  deployType: string
  installPath: string
  /** 运行状态：0-已停止 1-运行中 2-异常 3-停止中 5-部署中 6-启动中 */
  runStatus: number
  runStatusDesc: string
}

export const instanceApi = {
  // 查询实例运行状态（含基础信息，用于 Dashboard 展示服务器运行状态）
  getStatus: (instanceId: number) =>
    get<InstanceStatusVO>('/instance/status', { instanceId }),

  // 启动实例
  start: (instanceId: number) =>
    post<void>(`/instance/start?instanceId=${instanceId}`),

  // 停止实例
  stop: (instanceId: number) =>
    post<void>(`/instance/stop?instanceId=${instanceId}`),

  // 重启实例
  restart: (instanceId: number) =>
    post<void>(`/instance/restart?instanceId=${instanceId}`),
}

/**
 * 地图管理 API（Phase 3）
 */
export const mapApi = {
  // 获取地图列表
  list: (instanceId: number) => get<MapListVO[]>('/maps/list', { instanceId }),

  // 上传地图（小文件直传）
  upload: (file: File, instanceId: number, onProgress?: (p: number) => void) =>
    upload<MapListVO>(`/maps/upload?instanceId=${instanceId}`, file, onProgress),

  // 删除地图
  delete: (instanceId: number, mapName: string) =>
    del<void>(`/maps/${mapName}?instanceId=${instanceId}`),

  // 刷新地图列表
  refresh: (instanceId: number) => post<void>('/maps/refresh', { instanceId }),

  // 热重载地图（RCON）
  hotReload: (instanceId: number) => post<void>('/maps/hot-reload', { instanceId }),

  // 裁剪 VPK（移除无用资源）
  trim: (instanceId: number, mapName: string) =>
    post<VpkTrimResultVO>(`/maps/${mapName}/trim?instanceId=${instanceId}`),

  // 批量裁剪
  trimBatch: (data: { instanceId: number; mapNames: string[] }) =>
    post<VpkTrimResultVO[]>('/maps/trim-batch', data),

  // 获取 mission 信息
  mission: (instanceId: number, mapName: string) =>
    get<MissionInfoVO>(`/maps/${mapName}/mission`, { instanceId }),
}

/**
 * 分片上传 API（Phase 3）
 */
export const chunkUploadApi = {
  // 初始化分片上传会话
  init: (data: {
    instanceId: number
    filename: string
    totalSize: number
    totalChunks: number
    targetPath?: string
  }) => post<{ uploadId: string; chunkSize: number }>('/chunk-upload/init', data),

  // 上传单个分片
  uploadChunk: (
    uploadId: string,
    index: number,
    chunk: File,
    onProgress?: (p: number) => void
  ) => upload<void>(`/chunk-upload/${uploadId}/chunk?index=${index}`, chunk, onProgress),

  // 查询上传状态
  status: (uploadId: string) =>
    get<{
      uploadId: string
      totalChunks: number
      receivedChunks: number
      receivedIndexes: number[]
      status: string
      progress: number
    }>(`/chunk-upload/${uploadId}/status`),

  // 完成上传，触发合并
  complete: (uploadId: string) => post<void>(`/chunk-upload/${uploadId}/complete`),

  // 取消上传，清理已上传分片
  cancel: (uploadId: string) => post<void>(`/chunk-upload/${uploadId}/cancel`),
}

// ============ Phase 3 类型定义 ============

export interface MapListVO {
  title?: string
  vpkName?: string
  chapters?: Array<{ code: string; title?: string; modes?: string[] }>
}

export interface VpkTrimResultVO {
  fileName: string
  originalSize: number
  trimmedSize: number
  savedBytes: number
  totalEntries: number
  trimmedEntries: number
  backupCreated: boolean
  backupFileName?: string
}

export interface MissionInfoVO {
  vpkName: string
  title?: string
  chapters?: Array<{ code: string; title?: string; modes?: string[] }>
}

/**
 * 插件管理 API
 */
export const pluginApi = {
  // 获取插件列表
  getList: () => get<PluginInfo[]>('/plugins'),
  
  // 启用/禁用插件
  toggle: (filename: string, enabled: boolean) => 
    put<void>(`/plugins/${filename}/toggle`, { enabled }),
  
  // 上传插件
  upload: (file: File, onProgress?: (percent: number) => void) => 
    upload<PluginInfo>('/plugins/upload', file, onProgress),
  
  // 删除插件
  delete: (filename: string) => del<void>(`/plugins/${filename}`),
  
  // 获取预设列表
  getPresets: () => get<PresetConfig[]>('/plugins/presets'),
  
  // 应用预设
  applyPreset: (presetId: string) => post<void>(`/plugins/presets/${presetId}/apply`),
  
  // 创建预设
  createPreset: (preset: Omit<PresetConfig, 'id' | 'createdAt' | 'updatedAt'>) => 
    post<PresetConfig>('/plugins/presets', preset),
  
  // 删除预设
  deletePreset: (presetId: string) => del<void>(`/plugins/presets/${presetId}`)
}

/**
 * RCON 控制台 API
 *
 * 说明：RCON 命令历史为前端本地（localStorage）持久化，后端不提供 history 端点。
 */
export const rconApi = {
  // 执行命令（必须传 instanceId，对应后端 RconCommandDTO）
  execute: (instanceId: number, command: string) =>
    post<{ success: boolean; output?: string; error?: string; executionTime?: number }>(
      '/rcon/execute',
      { instanceId, command }
    ),
}

/**
 * 性能监控 API
 *
 * 注意：原 getCurrent/getStats 端点在后端 MonitorController 中并不存在，
 * 这里保留是为了向后兼容旧代码；新代码请使用 getStatus/getHistory/getConfig/setConfig。
 */
export const monitorApi = {
  // [遗留] 获取当前性能数据
  getCurrent: () => get<PerformanceData>('/monitor/current'),

  // [遗留] 获取历史性能数据
  getHistory: (params: {
    startTime: number
    endTime: number
    interval?: number
  }) => get<PerformanceData[]>('/monitor/history', params),

  // [遗留] 获取统计信息
  getStats: (period: 'hour' | 'day' | 'week') => get<{
    avgCpu: number
    avgMemory: number
    avgFps: number
    maxPlayers: number
    uptime: number
  }>('/monitor/stats', { period }),

  // ===== Phase 5.3 新增：对齐 MonitorController 实际端点 =====

  // 获取当前系统状态（GET /monitor/status?instanceId=...）
  getStatus: (instanceId: number) =>
    get<MonitorStatusVO>('/monitor/status', { instanceId }),

  // 获取历史监控数据（GET /monitor/history，支持自动降采样）
  getSystemHistory: (params: {
    instanceId: number
    startTime?: string
    endTime?: string
    timeRangeMinutes?: number
  }) => get<SystemMetricVO[]>('/monitor/history', params),

  // 获取监控配置
  getConfig: () => get<MonitorConfigVO>('/monitor/config'),

  // 更新监控采集开关
  setConfig: (data: { enable: boolean }) => post<void>('/monitor/config', data),
}

/**
 * 管理员管理 API
 */
export const adminApi = {
  // 获取管理员列表
  getList: () => get<AdminInfo[]>('/admins'),
  
  // 添加管理员
  add: (admin: Omit<AdminInfo, 'addedAt'>) => post<void>('/admins', admin),
  
  // 删除管理员
  delete: (steamId: string) => del<void>(`/admins/${steamId}`),
  
  // 更新管理员权限
  update: (steamId: string, data: { flags?: string; immunity?: number }) => 
    put<void>(`/admins/${steamId}`, data)
}

/**
 * 文件管理 API
 */
export const fileApi = {
  // 获取文件列表
  getList: (path?: string) => get<FileInfo[]>('/files', { path }),
  
  // 获取文件内容
  getContent: (path: string) => get<{ content: string }>('/files/content', { path }),
  
  // 更新文件内容
  updateContent: (path: string, content: string) => 
    put<void>('/files/content', { path, content }),
  
  // 删除文件
  delete: (path: string) => del<void>('/files', { path })
}

// === Phase 1: 运维核心 ===

/**
 * 服务器信息 API（hostname/motd/host）
 */
export const serverInfoApi = {
  get: (instanceId: number) => get<{ hostname: string; motd: string; host: string }>('/server-info/get', { instanceId }),
  update: (data: { instanceId: number; hostname?: string; motd?: string; host?: string }) =>
    post<void>('/server-info/update', data),
}

/**
 * SourceMod 日志 API
 */
export const logsApi = {
  listFiles: (instanceId: number) => get<Array<{
    name: string
    path: string
    size: number
    lastModified: number
    isErrorLog: boolean
  }>>('/logs/files', { instanceId }),
  getContent: (instanceId: number, file: string) =>
    get<string>('/logs/content', { instanceId, file }),
  /** SSE 流 URL（完整路径，不含 API_BASE 因为 SSE 需要绝对路径） */
  streamUrl: (instanceId: number, file: string) =>
    `/api/plugin/l4d2/logs/stream?instanceId=${instanceId}&file=${encodeURIComponent(file)}`,
}

/**
 * 备份还原 API
 */
export const backupApi = {
  list: (instanceId: number) => get<Array<{
    id: string
    name: string
    description?: string
    createdAt?: string
    owner?: string
    status?: string
  }>>('/backups/list', { instanceId }),
  create: (data: { instanceId: number; name: string; description?: string }) =>
    post<{ id: string }>('/backups/create', data),
  restore: (data: { instanceId: number; backupId: string }) =>
    post<void>('/backups/restore', data),
  rename: (data: { backupId: string; newName: string }) =>
    post<void>('/backups/rename', data),
  delete: (backupId: string) => del<void>(`/backups/${backupId}`),
  detail: (backupId: string) => get<{ id: string; name: string; description?: string }>(`/backups/${backupId}`),
}

// === Phase 2: 插件列表 VO（用于 pluginManageApi） ===

export interface PluginListVO {
  id?: number
  /** 插件名（plugins_store 子目录名，对齐后端 PluginListVO.name） */
  name: string
  /** 状态：enabled | disabled（对齐后端 PluginListVO.status） */
  status: string
  source?: string
  hasSmx?: boolean
  hasConfig?: boolean
  description?: string
  version?: string
  author?: string
  fileList?: string[]
  configFiles?: string[]
  enableTime?: string
  createTime?: string
  updateTime?: string
}

// === Phase 2: Plugin Store ===

/**
 * 插件商店 API（Phase 2.5）
 */
export const pluginStoreApi = {
  list: (params?: { keyword?: string; category?: string; page?: number; size?: number }) => get<Array<{
    pluginId: string
    name: string
    description?: string
    category?: string
    size?: number
    updatedAt?: string
  }>>('/plugin-store/list', params),
  detail: (pluginId: string) => get<{
    pluginId: string
    name: string
    description?: string
    category?: string
    size?: number
    updatedAt?: string
    readme: string
    fileList: Array<{ path: string; size: number }>
  }>(`/plugin-store/${pluginId}`),
  readme: (pluginId: string) => get<string>(`/plugin-store/${pluginId}/readme`),
  download: (data: { instanceId: number; pluginId: string; targetPath?: string }) =>
    post<string>('/plugin-store/download', data),
  tasks: (instanceId: number) => get<Array<{
    taskId: string
    instanceId: number
    pluginId: string
    status: string
    progress: number
    totalBytes: number
    downloadedBytes: number
    filename?: string
    error?: string
    startedAt?: string
    finishedAt?: string
  }>>('/plugin-store/tasks', { instanceId }),
  cancelTask: (taskId: string) => post<void>(`/plugin-store/tasks/${taskId}/cancel`),
}

// === Phase 2: Plugin Config ===

/**
 * 插件配置 API（Phase 2.6）
 */
export const pluginConfigApi = {
  get: (instanceId: number, pluginName: string) => get<{
    pluginName: string
    configName?: string
    configPath?: string
    items: Array<{
      key: string
      value: string
      defaultValue?: string
      min?: number
      max?: number
      description?: string
      lineNumber: number
    }>
    rawContent?: string
    lastSyncedAt?: string
  }>('/plugin-config/get', { instanceId, pluginName }),
  update: (data: {
    instanceId: number
    pluginName: string
    items: Array<{ key: string; value: string; lineNumber: number }>
  }) => post<void>('/plugin-config/update', data),
  candidates: (instanceId: number, pluginName: string) =>
    get<Array<{ path: string; exists: boolean }>>('/plugin-config/candidates', { instanceId, pluginName }),
}

// === Phase 2: Preset ===

/**
 * 预设场景 API（Phase 2.7）
 */
export const presetApi = {
  list: () => get<Array<{
    id: string
    name: string
    description?: string
    gameMode?: string
    maxPlayers?: number
  }>>('/presets/list'),
  detail: (presetId: string) => get<{
    id: string
    name: string
    description?: string
    gameMode?: string
    maxPlayers?: number
    platform?: string
    enabledPlugins: string[]
    disabledPlugins: string[]
    configOverrides: Array<{ file: string; items: Record<string, string> }>
  }>(`/presets/${presetId}`),
  apply: (presetId: string, instanceId: number) =>
    post<void>(`/presets/${presetId}/apply?instanceId=${instanceId}`),
}

// === Phase 2: Plugin Manage（新端点，与旧 pluginApi 并存） ===

/**
 * 内置插件清单项 VO（与后端 BuiltinPluginVO 对齐）
 */
export interface BuiltinPluginVO {
  id: string
  name: string
  category: 'platform' | 'required' | 'optional' | 'custom'
  fileName: string
  size: number
  platform: 'linux' | 'windows' | 'all'
  description?: string
  installed?: boolean
}

/**
 * 内置插件批量安装结果（与后端 InstallResult 对齐）
 */
export interface BuiltinInstallResultVO {
  pluginId?: string
  pluginName: string
  status: 'SUCCESS' | 'FAILED'
  message?: string
  installedFiles?: string[]
}

/**
 * 插件管理 API（Phase 2.8）
 */
export const pluginManageApi = {
  list: (instanceId: number) => get<PluginListVO[]>('/plugins/list', { instanceId }),
  upload: (file: File, instanceId: number, onProgress?: (percent: number) => void) =>
    upload<PluginListVO>(`/plugins/upload?instanceId=${instanceId}`, file, onProgress),
  delete: (instanceId: number, pluginName: string) =>
    del<void>(`/plugins/${pluginName}?instanceId=${instanceId}`),
  enableLoad: (instanceId: number, pluginName: string) =>
    post<void>(`/plugins/enable-load?instanceId=${instanceId}&pluginName=${encodeURIComponent(pluginName)}`),
  disableUnload: (instanceId: number, pluginName: string) =>
    post<void>(`/plugins/disable-unload?instanceId=${instanceId}&pluginName=${encodeURIComponent(pluginName)}`),
  batchEnable: (data: { instanceId: number; pluginNames: string[] }) =>
    post<void>('/plugins/batch-enable', data),
  batchDisable: (data: { instanceId: number; pluginNames: string[] }) =>
    post<void>('/plugins/batch-disable', data),
  exportAllStart: (instanceId: number) =>
    get<string>('/plugins/export-all/start', { instanceId }),
  exportAllStatus: (instanceId: number) => get<{
    taskId: string
    instanceId?: number
    status: string
    totalFiles: number
    processedFiles: number
    downloadUrl?: string
    error?: string
    startedAt?: string
    finishedAt?: string
  }>('/plugins/export-all/status', { instanceId }),
  exportAllDownloadUrl: (instanceId: number) =>
    `/api/plugin/l4d2/plugins/export-all/download?instanceId=${instanceId}`,
  exportAllCancel: (instanceId: number) =>
    post<void>('/plugins/export-all/cancel', { instanceId }),
  // 内置平台插件（SourceMod + Metamod）
  platformStatus: (instanceId: number) => get<boolean>('/plugins/platform/status', { instanceId }),
  installPlatform: (instanceId: number) => post<string>(`/plugins/platform/install?instanceId=${instanceId}`),
  // 内置插件市场（builtin-plugins.yaml + builtin-plugins/*.zip，共 62 个插件）
  listBuiltin: (instanceId: number) =>
    get<BuiltinPluginVO[]>('/plugins/builtin/list', { instanceId }),
  installBuiltin: (instanceId: number, pluginId: string) =>
    post<string>(`/plugins/builtin/${encodeURIComponent(pluginId)}/install?instanceId=${instanceId}`),
  batchInstallBuiltin: (data: { instanceId: number; pluginIds: string[] }) =>
    post<BuiltinInstallResultVO[]>('/plugins/builtin/batch-install', data),
}

// === Phase 4: 下载管理 ===

/**
 * 下载任务 VO
 */
export interface DownloadTaskVO {
  taskId: string
  instanceId: number
  taskType: 'URL' | 'WORKSHOP'
  taskUrl: string
  filename: string
  fileSize: number
  downloadedSize: number
  progress: number
  downloadSpeed: number
  status: 'PENDING' | 'DOWNLOADING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'PENDING_MANUAL'
  errorMessage?: string
  targetPath?: string
  workshopId?: string
  workshopTitle?: string
  previewUrl?: string
  startTime: string
  completeTime?: string
  formattedSpeed: string
  formattedSize: string
}

/**
 * URL 下载请求 DTO
 */
export interface UrlDownloadDTO {
  instanceId: number
  url: string
  filename?: string
  referer?: string
  targetPath?: string
}

/**
 * Workshop 下载请求 DTO
 */
export interface WorkshopDownloadDTO {
  instanceId: number
  workshopUrlOrId: string
}

/**
 * Workshop 解析条目 VO
 */
export interface WorkshopItemVO {
  publishedFileId: string
  title: string
  filename: string
  fileSize: string
  fileUrl: string
  previewUrl: string
  hasFileUrl: boolean
}

/**
 * Workshop 解析结果 VO
 */
export interface WorkshopParseResultVO {
  sourceId: string
  items: WorkshopItemVO[]
}

/**
 * 通用链接解析结果 VO
 */
export interface LinkParseResultVO {
  sourceType: string
  sourceId: string
  originalLink: string
  items?: WorkshopItemVO[]
}

/**
 * 下载管理 API（Phase 4）
 */
export const downloadApi = {
  // 创建 URL 下载任务（支持多 URL 切分，返回 taskId 列表）
  createUrlTask: (data: UrlDownloadDTO) => post<string[]>('/download/url', data),

  // 创建 Workshop 下载任务（返回 taskId 列表）
  createWorkshopTask: (data: WorkshopDownloadDTO) => post<string[]>('/download/workshop', data),

  // 解析 Workshop 链接，预览条目
  parseWorkshop: (url: string) => post<WorkshopParseResultVO>('/download/parse-workshop', { url }),

  // 解析任意链接，自动判断来源
  parseLink: (url: string) => post<LinkParseResultVO>('/download/parse-link', { url }),

  // 任务列表（可按状态过滤）
  listTasks: (instanceId: number, status?: string) =>
    get<DownloadTaskVO[]>('/download/tasks', { instanceId, status }),

  // 任务详情
  getTask: (taskId: string) => get<DownloadTaskVO>(`/download/tasks/${taskId}`),

  // 取消任务
  cancelTask: (taskId: string) => post<void>(`/download/tasks/${taskId}/cancel`),

  // 删除任务记录（仅终态）
  deleteTask: (taskId: string) => del<void>(`/download/tasks/${taskId}`),
}

// === Phase 5: 数据采集模块（玩家统计 + 游玩时长 + 监控重构） ===

/**
 * 玩家统计快照（与后端 PlayerStatSnapshotSpec 对齐）
 */
export interface PlayerStatSnapshot {
  instanceId: number
  timestamp: number
  serverOnline: boolean
  collectOk: boolean
  playerCount: number
  maxPlayers: number
  map: string
  hostname: string
  difficulty: string
  gameMode: string
  errorMessage: string
}

/**
 * 玩家统计配置 VO（与后端 PlayerStatsConfigVO 对齐）
 */
export interface PlayerStatsConfigVO {
  enabled: boolean
  intervalMinutes: number
  retentionDays: number
  lastSnapshot: PlayerStatSnapshot | null
}

/**
 * 玩家统计趋势 VO（小时/天聚合）
 */
export interface PlayerStatsTrendVO {
  timestamp: number
  avgPlayers: number | null
  peakPlayers: number | null
  uniquePlayers: number
  offlineSamples: number
  sampleCount: number
}

/**
 * 玩家搜索结果 VO
 */
export interface PlayerStatsPlayerVO {
  steamId: string
  name: string
  location: string
  ip: string
  lastSeen: number
  estimatedMinutes: number
  rank: number
}

/**
 * 玩家按日统计 VO
 */
export interface PlayerStatsDayVO {
  date: string
  onlineMinutes: number
  samples: number
  firstSeen: number
  lastSeen: number
}

/**
 * 玩家别名记录 VO
 */
export interface PlayerStatsAliasVO {
  name: string
  samples: number
  estimatedMinutes: number
  firstSeen: number
  lastSeen: number
}

/**
 * 游玩时长查询结果 VO（与后端 PlaytimeVO 对齐）
 */
export interface PlaytimeVO {
  steamId: string
  steamId64: string
  totalPlaytimeHours: number
  realPlaytimeHours: number
  source: string
}

/**
 * 系统监控指标 VO（用于 /monitor/history 响应）
 */
export interface SystemMetricVO {
  timestamp: number
  cpuPercent: number
  cpuMaxCore: number
  memUsed: number
  memTotal: number
  swapUsed: number
  netUpSpeed: number
  netDownSpeed: number
  diskUsed: number
  diskTotal: number
}

/**
 * 监控状态 VO（GET /monitor/status 响应）
 */
export interface MonitorStatusVO {
  instanceId: number
  timestamp: number
  cpuPercent: number
  cpuMaxCore: number
  memUsed: number
  memTotal: number
  memPercent: number
  swapUsed: number
  netUpSpeed: number
  netDownSpeed: number
  diskUsed: number
  diskTotal: number
  diskPercent: number
}

/**
 * 监控配置 VO
 */
export interface MonitorConfigVO {
  historyEnabled: boolean
  collectIntervalMs: number
  retentionMs: number
  maxPoints: number
  downsampleTo: number
  collectEnabled: boolean
}

/**
 * 玩家统计 API（Phase 5.1）
 */
export const playerStatsApi = {
  // 获取采集配置（含最近一次快照）
  getConfig: () => get<PlayerStatsConfigVO>('/player-stats/config'),

  // 更新采集开关
  setConfig: (data: { enable: boolean }) =>
    post<void>('/player-stats/config', data),

  // 趋势查询（按小时或天聚合）
  getHourly: (params: {
    instanceId: number
    start?: number
    end?: number
    bucket?: 'hour' | 'day'
  }) => get<PlayerStatsTrendVO[]>('/player-stats/hourly', params),

  // 玩家搜索（关键字模糊匹配 steamId / name）
  searchPlayers: (params: {
    instanceId: number
    keyword?: string
    start?: number
  }) => get<PlayerStatsPlayerVO[]>('/player-stats/players/search', params),

  // 玩家按日统计
  getPlayerDays: (
    steamId: string,
    params: { instanceId: number; start?: number }
  ) => get<PlayerStatsDayVO[]>(`/player-stats/players/${encodeURIComponent(steamId)}/days`, params),

  // 玩家别名记录
  getPlayerAliases: (
    steamId: string,
    params: { instanceId: number; start?: number }
  ) => get<PlayerStatsAliasVO[]>(`/player-stats/players/${encodeURIComponent(steamId)}/aliases`, params),
}

/**
 * 游玩时长 API（Phase 5.2）
 */
export const playtimeApi = {
  // 查询玩家 L4D2 游玩时长
  query: (steamId: string) => post<PlaytimeVO>('/playtime/query', { steamId }),
}

// === Phase 6: 服务器配置 / 重启管理 / 版本信息 ===

/**
 * 服务器配置 VO（与后端 ServerConfigVO 对齐）
 */
export interface ServerConfigVO {
  instanceId?: number
  hostname: string
  rconPassword: string
  svPassword: string
  maxPlayers: number
  visibleMaxPlayers: number
  mapName: string
  gameMode: string
  difficulty: string
  extraConfig: Record<string, string>
  customConfig: string
}

/**
 * 服务器配置更新 DTO（与后端 ServerConfigUpdateDTO 对齐）
 */
export interface ServerConfigUpdateDTO {
  instanceId: number
  hostname?: string
  rconPassword?: string
  svPassword?: string
  maxPlayers?: number
  visibleMaxPlayers?: number
  mapName?: string
  gameMode?: string
  difficulty?: string
  extraConfig?: Record<string, string>
  customConfig?: string
}

/**
 * 重启配置 VO（与后端 RestartConfigVO 对齐）
 */
export interface RestartConfigVO {
  byRcon: boolean
  containerName: string
  customCmd: string
  commandTimeoutMs?: number
  enabled: boolean
  availableModes: string[]
}

/**
 * 重启请求 DTO（与后端 RestartDTO 对齐）
 */
export interface RestartDTO {
  instanceId: number
  mode?: 'AUTO' | 'RCON' | 'COMMAND'
}

/**
 * 重启配置更新 DTO（与后端 RestartConfigUpdateDTO 对齐）
 */
export interface RestartConfigUpdateDTO {
  byRcon?: boolean
  containerName?: string
  customCmd?: string
  commandTimeoutMs?: number
  enabled?: boolean
}

/**
 * 构建信息 VO（与后端 BuildInfoVO 对齐）
 */
export interface BuildInfoVO {
  version: string
  commit: string
  buildTime: string
  jdkVersion: string
  pf4jVersion: string
  pluginId: string
  pluginDescription: string
  springBootVersion: string
}

/**
 * 服务器配置 API（Phase 6.1）
 */
export const serverConfigApi = {
  // 获取服务器配置（解析 server.cfg 为结构化 VO）
  get: (instanceId: number) =>
    get<ServerConfigVO>('/server-config/get', { instanceId }),

  // 更新服务器配置（写入 server.cfg 并同步多 tick 文件）
  update: (data: ServerConfigUpdateDTO) =>
    post<void>('/server-config/update', data),

  // 重载服务器配置（RCON 执行 exec server.cfg）
  reload: (instanceId: number) =>
    post<void>('/server-config/reload', { instanceId }),

  // 获取配置文件原始内容
  getFileContent: (instanceId: number, fileName: string) =>
    get<string>('/server-config/file-content', { instanceId, fileName }),

  // 更新配置文件原始内容
  updateFileContent: (instanceId: number, fileName: string, content: string) =>
    post<void>('/server-config/file-content', { instanceId, fileName, content }),
}

/**
 * 重启管理 API（Phase 6.2）
 */
export const restartApi = {
  // 重启服务器（默认 AUTO 模式，按配置决定 RCON/COMMAND）
  restart: (data: RestartDTO) => post<void>('/restart', data),

  // 强制 RCON 模式重启
  restartByRcon: (instanceId: number) =>
    post<void>(`/restart/rcon?instanceId=${instanceId}`),

  // 强制命令模式重启
  restartByCommand: (instanceId: number) =>
    post<void>(`/restart/command?instanceId=${instanceId}`),

  // 获取重启配置
  getConfig: () => get<RestartConfigVO>('/restart/config'),

  // 更新重启配置
  setConfig: (data: RestartConfigUpdateDTO) =>
    post<void>('/restart/config', data),
}

/**
 * 版本信息 API（Phase 6.3）
 */
export const versionApi = {
  // 获取完整版本信息
  get: () => get<BuildInfoVO>('/version'),

  // 获取版本号字符串
  getShort: () => get<string>('/version/short'),
}

export default {
  server: serverApi,
  instance: instanceApi,
  map: mapApi,
  plugin: pluginApi,
  rcon: rconApi,
  monitor: monitorApi,
  admin: adminApi,
  file: fileApi,
  pluginStore: pluginStoreApi,
  pluginConfig: pluginConfigApi,
  preset: presetApi,
  pluginManage: pluginManageApi,
  download: downloadApi,
  playerStats: playerStatsApi,
  playtime: playtimeApi,
  serverConfig: serverConfigApi,
  restart: restartApi,
  version: versionApi
}
