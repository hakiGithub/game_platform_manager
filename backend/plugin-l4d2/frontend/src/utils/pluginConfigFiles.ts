export interface PluginConfigItem {
  key: string
  value: string
  defaultValue?: string
  min?: number
  max?: number
  description?: string
  lineNumber: number
}

export interface PluginConfigFile {
  fileId: string
  configName: string
  configPath: string
  items: PluginConfigItem[]
  rawContent?: string
  exists: boolean
  status: 'OK' | 'EMPTY' | 'MISSING' | 'ERROR'
  error?: string
  lastSyncedAt?: string
}

export interface PluginConfigPayload {
  pluginName: string
  configName?: string
  configPath?: string
  items: PluginConfigItem[]
  rawContent?: string
  lastSyncedAt?: string
  configFiles?: PluginConfigFile[]
}

/**
 * Normalize both the multi-file response and the legacy single-file response.
 * Item objects are cloned so edits in one draft never mutate the API payload.
 */
export function normalizeConfigFiles(
  data: PluginConfigPayload | null | undefined
): PluginConfigFile[] {
  if (!data) return []
  const files = Array.isArray(data.configFiles) && data.configFiles.length
    ? data.configFiles
    : data.configPath
      ? [{
          fileId: data.configPath,
          configName: data.configName || fileNameOf(data.configPath),
          configPath: data.configPath,
          items: data.items || [],
          rawContent: data.rawContent,
          exists: true,
          status: (data.items || []).length ? 'OK' as const : 'EMPTY' as const,
          lastSyncedAt: data.lastSyncedAt
        }]
      : []
  return files.map(file => ({
    ...file,
    items: (file.items || []).map(item => ({ ...item })),
    status: file.status || (file.exists ? 'OK' : 'MISSING')
  }))
}

export function fileNameOf(path: string): string {
  return path.split('/').pop() || path
}

export function fileStatusLabel(status: PluginConfigFile['status']): string {
  return {
    OK: '可编辑',
    EMPTY: '无配置项',
    MISSING: '文件不存在',
    ERROR: '读取失败'
  }[status] || status
}

export function fileStatusType(
  status: PluginConfigFile['status']
): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'OK') return 'success'
  if (status === 'EMPTY') return 'info'
  if (status === 'ERROR') return 'danger'
  return 'warning'
}

export function resetFileDefaults(file: PluginConfigFile): void {
  file.items.forEach(item => {
    if (item.defaultValue !== undefined) item.value = item.defaultValue
  })
}

export function findFile(
  files: PluginConfigFile[],
  fileIdOrPath: string
): PluginConfigFile | undefined {
  return files.find(
    file => file.fileId === fileIdOrPath || file.configPath === fileIdOrPath
  )
}

/** Only a readable file with parsed items may be edited or saved. */
export function canEditConfigFile(file: PluginConfigFile): boolean {
  return file.items.length > 0 && (file.status === 'OK' || file.status === 'EMPTY')
}

/** Explain why temporary RCON application is unavailable for an item. */
export function tempConfigDisabledReason(
  file: PluginConfigFile,
  item: PluginConfigItem
): string {
  if (file.status === 'ERROR') return '配置文件读取失败，无法临时设置'
  if (file.status === 'MISSING') return '配置文件不存在，无法临时设置'
  if (!item.value.trim()) return '当前值不能为空'
  return ''
}

/** Preserve drafts while replacing server snapshots; only remove files no longer returned. */
export function mergeConfigFileDrafts(
  current: PluginConfigFile[],
  incoming: PluginConfigFile[]
): PluginConfigFile[] {
  return incoming.map(file => {
    const previous = findFile(current, file.fileId)
    return previous ? { ...file, items: previous.items } : file
  })
}
