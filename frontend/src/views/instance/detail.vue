<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getInstanceDetail,
  getInstanceMetrics,
  startInstance,
  stopInstance,
  restartInstance,
  stopServer,
  updateGame,
  getInstanceLogs,
  getInstanceConfig,
  updateInstanceConfig,
  getInstanceFiles,
  downloadFile,
  uploadFile,
  deleteFile,
  createDirectory,
  readFile,
  saveFile,
  sendConsoleCommand
} from '@/api/instance'
import { createInstanceLogStream, createInstanceConsole } from '@/utils/websocket'
import { statusType } from '@/utils/instanceStatus'
import { useBackupStore } from '@/stores/backup'
import BackupForm from '@/components/BackupForm.vue'
import RestoreConfirm from '@/components/RestoreConfirm.vue'
import BackupProgress from '@/components/BackupProgress.vue'
import { PluginTab } from '@/plugins'

const backupStore = useBackupStore()

const route = useRoute()
const router = useRouter()

// 实例ID
const instanceId = computed(() => route.params.id)

// 加载状态
const loading = ref(false)
// 动态资源数据首次加载状态（仅首次加载时显示"加载中..."，定时刷新静默更新）
const metricsLoading = ref(false)
// 是否已加载过动态数据（首次加载完成后设为 true，之后定时刷新不再显示加载提示）
const hasMetrics = ref(false)
// 动态资源数据加载错误信息
const metricsError = ref('')
// 动态资源数据定时刷新句柄
let metricsTimer = null

// 实例详情
const instanceInfo = ref({
  id: '',
  name: '',
  game: '',
  gameId: '',
  gameCode: '',
  deployType: '',
  hostId: '',
  hostName: '',
  ip: '',
  port: '',
  status: 'stopped',
  players: 0,
  maxPlayers: 0,
  cpu: 0,
  memory: 0,
  memoryUsageText: '',
  uptime: 0,
  createdAt: '',
  deployPath: ''
})

// 当前Tab
const activeTab = computed({
  get: () => route.query.tab || 'info',
  set: (val) => {
    router.replace({ query: { ...route.query, tab: val } })
  }
})

// Tab列表
const tabs = [
  { name: 'info', label: '基础信息', icon: 'InfoFilled' },
  { name: 'config', label: '配置管理', icon: 'Setting' },
  { name: 'files', label: '文件管理', icon: 'Folder' },
  { name: 'logs', label: '日志查看', icon: 'Tickets' },
  { name: 'console', label: '控制台', icon: 'Monitor' },
  { name: 'backup', label: '备份还原', icon: 'Download' },
  { name: 'plugin', label: '插件扩展', icon: 'Connection' }
]

// 获取实例详情（静态数据，快速响应）
async function fetchInstanceDetail() {
  loading.value = true
  try {
    const data = await getInstanceDetail(instanceId.value)
    // 后端 InstanceVO 字段映射到前端字段
    // 后端: instanceName/gameName/onlinePlayers/createTime/installPath/hostIp/portConfig/configInfo/runStatus
    // 前端: name/game/players/createdAt/deployPath/ip/port/maxPlayers
    const portConfig = data.portConfig || {}
    // 提取主端口：优先 game 端口，其次第一个端口值
    const mainPort = portConfig.game || portConfig.gamePort || portConfig.port ||
                     (Object.keys(portConfig).length > 0 ? portConfig[Object.keys(portConfig)[0]] : '')
    // 提取最大玩家数：从 configInfo 中查找
    const configInfo = data.configInfo || {}
    const maxPlayers = configInfo.maxPlayers || configInfo.MaxPlayers || configInfo.MAX_PLAYERS || 0
    // 格式化创建时间
    const createTime = data.createTime || ''
    const formattedCreatedAt = createTime
      ? (typeof createTime === 'string' ? createTime.replace('T', ' ').substring(0, 19) : '')
      : ''
    instanceInfo.value = {
      ...instanceInfo.value,
      ...data,
      // 字段名映射（覆盖后端字段名为前端字段名）
      name: data.instanceName || instanceInfo.value.name,
      game: data.gameName || instanceInfo.value.game,
      players: data.onlinePlayers != null ? data.onlinePlayers : instanceInfo.value.players,
      maxPlayers: maxPlayers || instanceInfo.value.maxPlayers,
      ip: data.hostIp || instanceInfo.value.ip,
      port: mainPort || instanceInfo.value.port,
      createdAt: formattedCreatedAt || instanceInfo.value.createdAt,
      deployPath: data.installPath || instanceInfo.value.deployPath,
      // 状态字符串后端已提供（status），保持原样
      status: data.status || instanceInfo.value.status
    }

    // 静态数据加载完成后，异步拉取动态资源数据（CPU/内存/运行时长等）
    // 不阻塞首屏渲染，仅在运行中实例上拉取
    if (data.status === 'running') {
      fetchInstanceMetrics()
    }
  } catch (error) {
    console.error('Failed to fetch instance detail:', error)
    ElMessage.error('获取实例详情失败')
  } finally {
    loading.value = false
  }
}

// 获取实例动态资源数据（CPU/内存/运行时长，异步加载）
// 该接口会触发后端 SSH/Docker 调用，响应较慢，单独加载不阻塞首屏
// @param isRefresh 是否为定时刷新（true 时静默更新，不显示 loading 状态）
async function fetchInstanceMetrics(isRefresh = false) {
  // 仅首次加载（非刷新）时显示 loading 状态
  // 定时刷新时保持旧值显示，接口返回后静默更新，避免转圈闪烁
  if (!isRefresh) {
    metricsLoading.value = true
  }
  metricsError.value = ''
  try {
    const data = await getInstanceMetrics(instanceId.value)
    if (data.available === false) {
      // 实例未运行或拉取失败，保留旧值不动
      metricsError.value = data.reason || '无法获取动态数据'
      return
    }
    // 更新动态字段（保留其他静态字段不变）
    instanceInfo.value = {
      ...instanceInfo.value,
      cpu: data.cpuUsage != null ? data.cpuUsage : instanceInfo.value.cpu,
      memory: data.memoryUsage != null ? data.memoryUsage : instanceInfo.value.memory,
      memoryUsageText: data.memoryUsageText || instanceInfo.value.memoryUsageText,
      uptime: data.uptime != null ? data.uptime : instanceInfo.value.uptime,
      players: data.onlinePlayers != null ? data.onlinePlayers : instanceInfo.value.players
    }
    hasMetrics.value = true
  } catch (error) {
    console.error('Failed to fetch instance metrics:', error)
    metricsError.value = error.message || '动态数据加载失败'
  } finally {
    if (!isRefresh) {
      metricsLoading.value = false
    }
  }
}

// 启动动态数据定时刷新（每 15 秒刷新一次，仅在运行中实例上生效）
function startMetricsAutoRefresh() {
  stopMetricsAutoRefresh()
  metricsTimer = setInterval(() => {
    if (instanceInfo.value.status === 'running') {
      // 定时刷新：静默更新，不显示 loading 转圈
      fetchInstanceMetrics(true)
    }
  }, 15000)
}

// 停止动态数据定时刷新
function stopMetricsAutoRefresh() {
  if (metricsTimer) {
    clearInterval(metricsTimer)
    metricsTimer = null
  }
}

// 获取部署类型中文展示
function getDeployTypeText(deployType) {
  if (!deployType) return '-'
  const texts = {
    'linuxgsm': 'LinuxGSM 部署',
    'docker': 'Docker 部署',
    'docker-compose': 'Docker Compose 部署',
    'linuxgsm-docker': 'LinuxGSM Docker 部署'
  }
  return texts[deployType] || deployType
}

// 获取部署类型对应的 tag 样式（不同部署类型用不同颜色区分）
function getDeployTypeTagType(deployType) {
  const types = {
    'linuxgsm': 'warning',          // 橙色 - 传统 LinuxGSM
    'docker': 'primary',            // 蓝色 - Docker
    'docker-compose': 'success',    // 绿色 - Docker Compose
    'linuxgsm-docker': 'danger'     // 红色 - LinuxGSM Docker（最新集成）
  }
  return types[deployType] || 'info'
}

// 获取部署类型简短描述（用于 header 展示）
function getDeployTypeShort(deployType) {
  const shorts = {
    'linuxgsm': 'LinuxGSM',
    'docker': 'Docker',
    'docker-compose': 'Compose',
    'linuxgsm-docker': 'LGSM Docker'
  }
  return shorts[deployType] || deployType || '-'
}

// 操作处理
const actionLoading = ref(false)

async function handleStart() {
  try {
    actionLoading.value = true
    await startInstance(instanceId.value)
    ElMessage.success('启动成功')
    fetchInstanceDetail()
  } catch (error) {
    console.error('Failed to start instance:', error)
    ElMessage.error('启动失败: ' + (error.message || '未知错误'))
  } finally {
    actionLoading.value = false
  }
}

async function handleStop() {
  try {
    await ElMessageBox.confirm('确定要停止实例吗？', '确认操作', { type: 'warning' })
    actionLoading.value = true
    await stopInstance(instanceId.value)
    ElMessage.success('停止成功')
    fetchInstanceDetail()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to stop instance:', error)
      ElMessage.error('停止失败: ' + (error.message || '未知错误'))
    }
  } finally {
    actionLoading.value = false
  }
}

async function handleRestart() {
  try {
    await ElMessageBox.confirm('确定要重启实例吗？', '确认操作', { type: 'warning' })
    actionLoading.value = true
    await restartInstance(instanceId.value)
    ElMessage.success('重启成功')
    fetchInstanceDetail()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to restart instance:', error)
      ElMessage.error('重启失败: ' + (error.message || '未知错误'))
    }
  } finally {
    actionLoading.value = false
  }
}

// linuxgsm-docker 部署：游戏进程级操作按钮可见性
const isLinuxGsmDocker = computed(() => instanceInfo.value.deployType === 'linuxgsm-docker')

// linuxgsm-docker：停止游戏服务器进程（容器保持运行）
async function handleStopServer() {
  try {
    await ElMessageBox.confirm('确定要停止游戏服务器进程吗？（容器保持运行）', '确认操作', { type: 'warning' })
    actionLoading.value = true
    await stopServer(instanceId.value)
    ElMessage.success('服务器已停止')
    fetchInstanceDetail()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to stop server:', error)
      ElMessage.error('停止服务器失败: ' + (error.message || '未知错误'))
    }
  } finally {
    actionLoading.value = false
  }
}

// linuxgsm-docker：更新游戏服务器（可能耗时数分钟）
async function handleUpdateGame() {
  try {
    await ElMessageBox.confirm('确定要更新游戏服务器吗？更新过程可能需要数分钟。', '确认操作', { type: 'warning' })
    actionLoading.value = true
    await updateGame(instanceId.value)
    ElMessage.success('服务器更新成功')
    fetchInstanceDetail()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to update game:', error)
      ElMessage.error('更新服务器失败: ' + (error.message || '未知错误'))
    }
  } finally {
    actionLoading.value = false
  }
}

// ========== 配置管理 ==========
const configForm = ref({})
const configLoading = ref(false)
const configSaving = ref(false)
const configMode = ref('form') // form | editor
const configEditorContent = ref('')
const configFileName = ref('server.properties')
const configFiles = ref([])

async function fetchConfig() {
  configLoading.value = true
  try {
    const data = await getInstanceConfig(instanceId.value, { configFile: configFileName.value })
    if (data.content) {
      configEditorContent.value = data.content
      // 尝试解析为表单
      try {
        configForm.value = parseConfigFile(data.content)
      } catch (e) {
        configForm.value = {}
      }
    } else {
      // 后端返回 { instanceId, configInfo }，使用 configInfo 作为表单数据
      const configInfo = data.configInfo || {}
      // 将所有值转为字符串，避免对象/数组在 el-input 中显示 [object Object]
      const form = {}
      Object.entries(configInfo).forEach(([key, value]) => {
        form[key] = typeof value === 'object' && value !== null
          ? JSON.stringify(value)
          : String(value)
      })
      configForm.value = form
      configEditorContent.value = JSON.stringify(configInfo, null, 2)
    }
    configFiles.value = data.availableFiles || []
  } catch (error) {
    console.error('Failed to fetch config:', error)
    ElMessage.error('获取配置失败')
  } finally {
    configLoading.value = false
  }
}

// 解析配置文件
function parseConfigFile(content) {
  const config = {}
  const lines = content.split('\n')
  lines.forEach(line => {
    line = line.trim()
    if (line && !line.startsWith('#')) {
      const [key, ...valueParts] = line.split('=')
      if (key && valueParts.length > 0) {
        config[key.trim()] = valueParts.join('=').trim()
      }
    }
  })
  return config
}

// 生成配置文件内容
function generateConfigContent(formData) {
  return Object.entries(formData)
    .map(([key, value]) => `${key}=${value}`)
    .join('\n')
}

async function saveConfig() {
  configSaving.value = true
  try {
    let content
    if (configMode.value === 'editor') {
      content = configEditorContent.value
    } else {
      content = generateConfigContent(configForm.value)
      configEditorContent.value = content
    }
    
    await updateInstanceConfig(instanceId.value, {
      configFile: configFileName.value,
      content: content,
      restart: false
    })
    ElMessage.success('配置保存成功')
    
    // 询问是否重启
    const shouldRestart = await ElMessageBox.confirm(
      '配置已保存，是否重启实例使配置生效？',
      '重启确认',
      {
        confirmButtonText: '立即重启',
        cancelButtonText: '稍后手动重启',
        type: 'warning'
      }
    ).catch(() => false)
    
    if (shouldRestart) {
      await handleRestart()
    }
  } catch (error) {
    console.error('Failed to save config:', error)
    ElMessage.error('保存失败: ' + (error.message || '未知错误'))
  } finally {
    configSaving.value = false
  }
}

// ========== 文件管理 ==========
const fileList = ref([])
const currentPath = ref('/')
const fileLoading = ref(false)
const fileUploading = ref(false)
const uploadDialogVisible = ref(false)
const uploadFileList = ref([])
const createDirDialogVisible = ref(false)
const newDirName = ref('')
const fileEditorVisible = ref(false)
const editingFile = ref({ name: '', path: '', content: '' })
const fileEditorSaving = ref(false)

async function fetchFiles(path = '/') {
  fileLoading.value = true
  currentPath.value = path
  try {
    const data = await getInstanceFiles(instanceId.value, { path })
    fileList.value = data.files || []
  } catch (error) {
    console.error('Failed to fetch files:', error)
    ElMessage.error('获取文件列表失败')
  } finally {
    fileLoading.value = false
  }
}

function handleFileClick(file) {
  if (file.isDirectory) {
    fetchFiles(file.path)
  } else {
    // 检查是否可编辑
    const editableExtensions = ['.txt', '.properties', '.yml', '.yaml', '.json', '.xml', '.conf', '.cfg', '.log', '.sh', '.bat']
    const ext = file.name.substring(file.name.lastIndexOf('.')).toLowerCase()
    
    if (editableExtensions.includes(ext) || file.size < 1024 * 1024) {
      openFileEditor(file)
    } else {
      handleFileDownload(file)
    }
  }
}

async function openFileEditor(file) {
  try {
    const data = await readFile(instanceId.value, file.path)
    editingFile.value = {
      name: file.name,
      path: file.path,
      content: data.content || ''
    }
    fileEditorVisible.value = true
  } catch (error) {
    console.error('Failed to read file:', error)
    ElMessage.error('读取文件失败')
  }
}

async function saveFileContent() {
  fileEditorSaving.value = true
  try {
    await saveFile(instanceId.value, editingFile.value.path, editingFile.value.content)
    ElMessage.success('文件保存成功')
    fileEditorVisible.value = false
  } catch (error) {
    console.error('Failed to save file:', error)
    ElMessage.error('保存文件失败')
  } finally {
    fileEditorSaving.value = false
  }
}

async function handleFileDownload(file) {
  try {
    const blob = await downloadFile(instanceId.value, file.path)
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.name
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('下载开始')
  } catch (error) {
    console.error('Failed to download file:', error)
    ElMessage.error('下载失败')
  }
}

async function handleFileDelete(file) {
  try {
    await ElMessageBox.confirm(
      `确定要删除 ${file.name} 吗？${file.isDirectory ? '目录及其所有内容将被删除。' : ''}`,
      '确认删除',
      { type: 'danger' }
    )
    await deleteFile(instanceId.value, file.path)
    ElMessage.success('删除成功')
    fetchFiles(currentPath.value)
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to delete file:', error)
      ElMessage.error('删除失败')
    }
  }
}

function goBackPath() {
  const parts = currentPath.value.split('/').filter(Boolean)
  parts.pop()
  const newPath = '/' + parts.join('/')
  fetchFiles(newPath || '/')
}

/** 面包屑跳转：跳到第 index 级目录（index 基于 currentPath 分段） */
function navigateToPath(index) {
  const parts = currentPath.value.split('/').filter(Boolean)
  const target = '/' + parts.slice(0, index + 1).join('/')
  if (target !== currentPath.value) {
    fetchFiles(target)
  }
}

async function handleCreateDirectory() {
  if (!newDirName.value.trim()) {
    ElMessage.warning('请输入目录名称')
    return
  }
  
  try {
    const newPath = currentPath.value === '/' 
      ? `/${newDirName.value}` 
      : `${currentPath.value}/${newDirName.value}`
    await createDirectory(instanceId.value, newPath)
    ElMessage.success('目录创建成功')
    createDirDialogVisible.value = false
    newDirName.value = ''
    fetchFiles(currentPath.value)
  } catch (error) {
    console.error('Failed to create directory:', error)
    ElMessage.error('创建目录失败')
  }
}

async function handleFileUpload() {
  if (uploadFileList.value.length === 0) {
    ElMessage.warning('请选择要上传的文件')
    return
  }
  
  fileUploading.value = true
  try {
    for (const file of uploadFileList.value) {
      const formData = new FormData()
      formData.append('file', file.raw)
      formData.append('path', currentPath.value)
      formData.append('overwrite', 'true')
      await uploadFile(instanceId.value, formData)
    }
    ElMessage.success('上传成功')
    uploadDialogVisible.value = false
    uploadFileList.value = []
    fetchFiles(currentPath.value)
  } catch (error) {
    console.error('Failed to upload file:', error)
    ElMessage.error('上传失败')
  } finally {
    fileUploading.value = false
  }
}

function formatFileSize(bytes) {
  if (!bytes) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = bytes
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }
  return `${size.toFixed(1)} ${units[unitIndex]}`
}

function formatFileTime(timestamp) {
  if (!timestamp) return '-'
  const d = new Date(timestamp)
  if (isNaN(d.getTime())) return '-'
  return d.toLocaleString()
}

// ========== 日志查看 ==========
const logs = ref([])
const logsLoading = ref(false)
const logsAutoRefresh = ref(false)
const logsSearchKeyword = ref('')
const logsLevelFilter = ref('')
const logWsClient = ref(null)
const logsContainerRef = ref(null)
const recentLogs = computed(() => logs.value.slice(-6))

async function fetchLogs() {
  logsLoading.value = true
  try {
    const params = {
      lines: 500,
      keyword: logsSearchKeyword.value || undefined,
      level: logsLevelFilter.value || undefined
    }
    const data = await getInstanceLogs(instanceId.value, params)
    logs.value = data.logs || []
    scrollLogsToBottom()
  } catch (error) {
    console.error('Failed to fetch logs:', error)
  } finally {
    logsLoading.value = false
  }
}

function connectLogWebSocket() {
  if (logWsClient.value) {
    logWsClient.value.close()
  }
  
  logWsClient.value = createInstanceLogStream({
    instanceId: instanceId.value,
    onMessage: (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'log') {
          // 兼容两种后端格式: {log: {time,level,message}} 或 {data: 原始文本行}
          const entry = data.log
          if (entry && typeof entry === 'object') {
            logs.value.push(entry)
          } else {
            logs.value.push({
              time: data.time || '',
              level: 'info',
              message: data.data || entry || ''
            })
          }
          if (logs.value.length > 1000) {
            logs.value = logs.value.slice(-1000)
          }
          if (logsAutoRefresh.value) {
            scrollLogsToBottom()
          }
        }
      } catch (e) {
        // 纯文本日志
        logs.value.push({
          time: new Date().toLocaleTimeString(),
          level: 'info',
          message: event.data
        })
      }
    },
    onOpen: () => {
      console.log('Log WebSocket connected')
    },
    onClose: () => {
      console.log('Log WebSocket closed')
    },
    onError: (error) => {
      console.error('Log WebSocket error:', error)
    }
  })
  
  logWsClient.value.connect()
}

function scrollLogsToBottom() {
  nextTick(() => {
    if (logsContainerRef.value) {
      logsContainerRef.value.scrollTop = logsContainerRef.value.scrollHeight
    }
  })
}

function toggleAutoRefresh() {
  logsAutoRefresh.value = !logsAutoRefresh.value
  if (logsAutoRefresh.value) {
    scrollLogsToBottom()
  }
}

function handleLogsSearch() {
  fetchLogs()
}

async function handleLogsDownload() {
  try {
    const content = logs.value.map(log => 
      `[${log.time}] [${log.level?.toUpperCase()}] ${log.message}`
    ).join('\n')
    
    const blob = new Blob([content], { type: 'text/plain' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `instance-${instanceId.value}-logs-${new Date().toISOString().slice(0, 10)}.log`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('日志下载开始')
  } catch (error) {
    console.error('Failed to download logs:', error)
    ElMessage.error('下载失败')
  }
}

// ========== 控制台 ==========
const consoleInput = ref('')
const consoleHistory = ref([])
const consoleWsClient = ref(null)
const consoleContainerRef = ref(null)
const consoleConnected = ref(false)
const consoleConnecting = ref(false)

function connectConsoleWebSocket() {
  if (consoleWsClient.value) {
    consoleWsClient.value.close()
  }
  
  consoleConnecting.value = true
  consoleWsClient.value = createInstanceConsole({
    instanceId: instanceId.value,
    onMessage: (event) => {
      try {
        const msg = JSON.parse(event.data)
        // 后端 WsMessage 结构为 { type, data }，data 字段为实际内容
        // type: connected / info / output / error / pong
        if (msg.type === 'output') {
          addConsoleOutput(msg.data || '', 'output')
        } else if (msg.type === 'error') {
          addConsoleOutput(msg.data || '未知错误', 'error')
        } else if (msg.type === 'connected') {
          addConsoleOutput(msg.data || '控制台连接成功', 'system')
        } else if (msg.type === 'info') {
          addConsoleOutput(msg.data || '', 'system')
        }
        // pong (心跳响应) 静默忽略
      } catch (e) {
        addConsoleOutput(event.data, 'output')
      }
    },
    onOpen: () => {
      consoleConnected.value = true
      consoleConnecting.value = false
      // 不在此处添加"控制台连接成功"，后端会通过 {type:"connected"} 消息发送
    },
    onClose: () => {
      consoleConnected.value = false
      consoleConnecting.value = false
      addConsoleOutput('控制台连接已断开', 'system')
    },
    onError: (error) => {
      console.error('Console WebSocket error:', error)
      consoleConnecting.value = false
      addConsoleOutput('控制台连接错误', 'error')
    }
  })
  
  consoleWsClient.value.connect()
}

function addConsoleOutput(content, type = 'output') {
  consoleHistory.value.push({
    type,
    content,
    time: new Date().toLocaleTimeString()
  })
  
  // 限制历史记录数量
  if (consoleHistory.value.length > 500) {
    consoleHistory.value = consoleHistory.value.slice(-500)
  }
  
  scrollConsoleToBottom()
}

function scrollConsoleToBottom() {
  nextTick(() => {
    if (consoleContainerRef.value) {
      consoleContainerRef.value.scrollTop = consoleContainerRef.value.scrollHeight
    }
  })
}

async function sendCommand() {
  if (!consoleInput.value.trim()) return
  
  const command = consoleInput.value.trim()
  addConsoleOutput(command, 'input')
  
  if (consoleWsClient.value && consoleWsClient.value.isConnected()) {
    // 后端 WsMessage 结构为 { type, data }，命令内容放在 data 字段
    consoleWsClient.value.send({
      type: 'command',
      data: command
    })
  } else {
    // 使用HTTP API作为后备
    try {
      const result = await sendConsoleCommand(instanceId.value, command)
      if (result.output) {
        addConsoleOutput(result.output, 'output')
      }
    } catch (error) {
      addConsoleOutput('发送命令失败: ' + (error.message || '未知错误'), 'error')
    }
  }
  
  consoleInput.value = ''
}

function clearConsole() {
  consoleHistory.value = []
}

// ========== 备份还原 ==========
const backupList = computed(() => backupStore.backupList)
const backupLoading = computed(() => backupStore.loading)
const backupPagination = computed(() => backupStore.pagination)
const activeBackupProgress = computed(() => backupStore.activeBackupProgress)
const activeRestoreProgress = computed(() => backupStore.activeRestoreProgress)

// 备份相关状态
const backupFormVisible = ref(false)
const creatingBackup = ref(false)
const selectedBackups = ref([])
const backupTypeFilter = ref('')
const backupStatusFilter = ref('')

// 还原相关状态
const restoreDialogVisible = ref(false)
const currentRestoreBackup = ref(null)
const restoringBackup = ref(false)

// 验证相关状态
const verifyingBackup = ref(null)

async function fetchBackups() {
  try {
    await backupStore.fetchBackupList(instanceId.value, {
      type: backupTypeFilter.value || undefined,
      status: backupStatusFilter.value || undefined
    })
  } catch (error) {
    console.error('Failed to fetch backups:', error)
    ElMessage.error('获取备份列表失败')
  }
}

async function handleCreateBackup(formData) {
  creatingBackup.value = true
  try {
    if (formData.type === 'database') {
      await backupStore.createDatabase(instanceId.value, {
        name: formData.name,
        description: formData.description
      })
    } else {
      await backupStore.createFiles(instanceId.value, {
        name: formData.name,
        description: formData.description,
        includePaths: formData.includePaths,
        excludePaths: formData.excludePaths
      })
    }
    ElMessage.success('备份任务已创建')
    backupFormVisible.value = false
    // 延迟刷新列表，等待备份开始
    setTimeout(() => fetchBackups(), 1000)
  } catch (error) {
    console.error('Failed to create backup:', error)
    ElMessage.error('创建备份失败: ' + (error.message || '未知错误'))
  } finally {
    creatingBackup.value = false
  }
}

async function handleCancelBackup(backup) {
  try {
    await ElMessageBox.confirm(
      `确定要取消备份「${backup.name}」吗？`,
      '确认取消',
      { type: 'warning' }
    )
    await backupStore.cancel(instanceId.value, backup.id)
    ElMessage.success('备份已取消')
    fetchBackups()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to cancel backup:', error)
      ElMessage.error('取消备份失败')
    }
  }
}

function handleRestore(backup) {
  currentRestoreBackup.value = backup
  restoreDialogVisible.value = true
}

async function handleConfirmRestore(backup) {
  restoringBackup.value = true
  try {
    await backupStore.restore(instanceId.value, backup.id, {
      restoreDatabase: backup.type === 'database' || backup.type === 'full',
      restoreFiles: backup.type === 'files' || backup.type === 'full'
    })
    ElMessage.success('还原任务已启动')
  } catch (error) {
    console.error('Failed to restore backup:', error)
    ElMessage.error('还原失败: ' + (error.message || '未知错误'))
  } finally {
    restoringBackup.value = false
  }
}

async function handleDeleteBackup(backup) {
  try {
    await ElMessageBox.confirm(
      `确定要删除备份「${backup.name}」吗？此操作不可恢复。`,
      '确认删除',
      { type: 'danger' }
    )
    await backupStore.remove(instanceId.value, backup.id)
    ElMessage.success('删除成功')
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to delete backup:', error)
      ElMessage.error('删除失败')
    }
  }
}

async function handleBatchDelete() {
  if (selectedBackups.value.length === 0) {
    ElMessage.warning('请选择要删除的备份')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${selectedBackups.value.length} 个备份吗？此操作不可恢复。`,
      '确认删除',
      { type: 'danger' }
    )
    await backupStore.batchRemove(instanceId.value, selectedBackups.value)
    ElMessage.success('批量删除成功')
    selectedBackups.value = []
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to batch delete backups:', error)
      ElMessage.error('批量删除失败')
    }
  }
}

async function handleDownloadBackup(backup) {
  try {
    const fileName = `${backup.name || 'backup'}-${backup.id}.zip`
    await backupStore.download(instanceId.value, backup.id, fileName)
    ElMessage.success('下载开始')
  } catch (error) {
    console.error('Failed to download backup:', error)
    ElMessage.error('下载失败')
  }
}

async function handleVerifyBackup(backup) {
  verifyingBackup.value = backup.id
  try {
    const result = await backupStore.verify(instanceId.value, backup.id)
    if (result.valid) {
      ElMessage.success('备份验证通过: ' + result.message)
    } else {
      ElMessage.warning('备份验证失败: ' + result.message)
    }
  } catch (error) {
    console.error('Failed to verify backup:', error)
    ElMessage.error('验证失败')
  } finally {
    verifyingBackup.value = null
  }
}

function handleBackupSelectionChange(selection) {
  selectedBackups.value = selection.map(item => item.id)
}

function handleBackupPageChange(page) {
  backupStore.updatePagination({ current: page })
  fetchBackups()
}

function handleBackupSizeChange(size) {
  backupStore.updatePagination({ size, current: 1 })
  fetchBackups()
}

function handleBackupFilterChange() {
  backupStore.updatePagination({ current: 1 })
  fetchBackups()
}

// 获取备份状态类型
function getBackupStatusType(status) {
  return backupStore.getBackupStatusType(status)
}

// 获取备份状态文本
function getBackupStatusText(status) {
  return backupStore.getBackupStatusText(status)
}

// 获取备份类型文本
function getBackupTypeText(type) {
  return backupStore.getBackupTypeText(type)
}

// 格式化运行时间
function formatUptime(seconds) {
  if (!seconds) return '-'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const mins = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days}天${hours}小时`
  if (hours > 0) return `${hours}小时${mins}分钟`
  return `${mins}分钟`
}

// 运行时元数据（docker-compose 部署产生的卷路径、容器ID等）
const runtimeMetadata = computed(() => instanceInfo.value.runtimeMetadata || null)

// 卷路径列表（兼容对象/数组两种结构）
const volumePathList = computed(() => {
  const meta = runtimeMetadata.value
  if (!meta || !meta.volumePaths) return []
  const vp = meta.volumePaths
  if (Array.isArray(vp)) {
    return vp.map((item) => ({ name: item.name, path: item.path }))
  }
  // 对象结构 { volumeName: hostPath }
  return Object.entries(vp).map(([name, path]) => ({ name, path }))
})

// 复制到剪贴板
async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    ElMessage.warning('复制失败，请手动复制')
  }
}

// 监听Tab切换
watch(activeTab, (val) => {
  if (val === 'config') fetchConfig()
  if (val === 'files') fetchFiles()
  if (val === 'logs') {
    fetchLogs()
    connectLogWebSocket()
  } else {
    if (logWsClient.value) {
      logWsClient.value.close()
    }
  }
  if (val === 'console') {
    connectConsoleWebSocket()
  } else {
    if (consoleWsClient.value) {
      consoleWsClient.value.close()
    }
  }
  if (val === 'backup') fetchBackups()
}, { immediate: true })

onMounted(() => {
  fetchInstanceDetail()
  // 启动动态资源数据定时刷新（运行中实例每 15 秒刷新一次 CPU/内存/运行时长）
  startMetricsAutoRefresh()
})

onBeforeUnmount(() => {
  // 停止动态数据定时刷新
  stopMetricsAutoRefresh()
  if (logWsClient.value) {
    logWsClient.value.close()
  }
  if (consoleWsClient.value) {
    consoleWsClient.value.close()
  }
})
</script>

<template>
  <div class="instance-detail-container">
    <!-- 顶部信息栏 -->
    <el-card class="info-card" shadow="never" v-loading="loading">
      <div class="command-identity">
        <div class="command-kicker">
          <span class="live-dot" :class="{ 'is-live': instanceInfo.status === 'running' }" />
          INSTANCE CONTROL ROOM
          <span class="command-channel">INSTANCE / {{ String(instanceInfo.id || instanceId).padStart(2, '0') }}</span>
        </div>
        <span class="command-hint">
          {{ instanceInfo.status === 'running' ? 'LIVE RUNTIME' : 'MANUAL ACTION REQUIRED' }}
        </span>
      </div>
      <div class="command-header">
        <div class="header-left">
          <el-button class="back-button" link @click="router.push('/instance/list')">
            <el-icon><ArrowLeft /></el-icon>
            返回列表
          </el-button>
          <div class="instance-mark"><el-icon :size="22"><Grid /></el-icon></div>
          <div class="instance-title">
            <span class="title-kicker">SERVICE INSTANCE</span>
            <div class="title-line">
              <h1>{{ instanceInfo.name }}</h1>
              <el-tag :type="statusType(instanceInfo.status)" size="small" effect="dark">
                {{ instanceInfo.runStatusDesc }}
              </el-tag>
            </div>
            <div class="instance-meta">
              <span><el-icon><Grid /></el-icon> {{ instanceInfo.game }}</span>
              <span><el-icon><Cpu /></el-icon> {{ getDeployTypeShort(instanceInfo.deployType) }}</span>
              <span><el-icon><Monitor /></el-icon> {{ instanceInfo.hostName }}</span>
              <span><el-icon><Link /></el-icon> {{ instanceInfo.ip }}:{{ instanceInfo.port }}</span>
            </div>
          </div>
        </div>

        <div class="command-state">
          <span class="command-state__label">RUNTIME STATE</span>
          <strong>{{ instanceInfo.status === 'running' ? 'LIVE' : 'STANDBY' }}</strong>
          <small>{{ instanceInfo.status === 'running' ? '接受实时遥测与运维指令' : '启动实例后恢复实时遥测' }}</small>
        </div>
      </div>

      <div class="command-dashboard" aria-label="实例运行指标">
        <div class="command-stat command-stat--players">
          <span class="stat-label">ONLINE PLAYERS</span>
          <strong>{{ instanceInfo.players || 0 }}<small> / {{ instanceInfo.maxPlayers || 0 }}</small></strong>
          <div class="player-track"><span :style="{ width: `${instanceInfo.maxPlayers ? Math.min(100, (instanceInfo.players / instanceInfo.maxPlayers) * 100) : 0}%` }" /></div>
        </div>
        <div class="command-stat">
          <span class="stat-label">CPU LOAD</span>
          <strong>{{ hasMetrics ? `${Math.round(instanceInfo.cpu || 0)}%` : '-' }}</strong>
          <el-progress class="command-progress" :percentage="Math.round(instanceInfo.cpu || 0)" :show-text="false" :stroke-width="5" />
        </div>
        <div class="command-stat">
          <span class="stat-label">MEMORY</span>
          <strong>{{ hasMetrics ? `${Math.round(instanceInfo.memory || 0)}%` : '-' }}</strong>
          <small class="stat-note">{{ hasMetrics ? (instanceInfo.memoryUsageText || '动态采样') : '等待遥测' }}</small>
          <el-progress class="command-progress" :percentage="Math.round(instanceInfo.memory || 0)" :show-text="false" :stroke-width="5" status="success" />
        </div>
        <div class="command-stat">
          <span class="stat-label">UPTIME</span>
          <strong>{{ instanceInfo.status === 'running' && hasMetrics ? formatUptime(instanceInfo.uptime) : '-' }}</strong>
          <small class="stat-note">{{ instanceInfo.status === 'running' ? '动态采样' : '实例未运行' }}</small>
        </div>
        <div class="command-action-rail">
          <span class="action-label">COMMAND DECK</span>
          <div class="header-right">
            <template v-if="instanceInfo.status === 'running'">
              <el-button type="warning" :loading="actionLoading" @click="handleStop">
                <el-icon><VideoPause /></el-icon>
                停止
              </el-button>
              <el-button type="info" :loading="actionLoading" @click="handleRestart">
                <el-icon><RefreshRight /></el-icon>
                重启
              </el-button>
              <!-- linuxgsm-docker：游戏进程级操作（容器级停止见上方"停止"） -->
              <template v-if="isLinuxGsmDocker">
                <el-button type="warning" plain :loading="actionLoading" @click="handleStopServer">
                  <el-icon><VideoPause /></el-icon>
                  停止服务器
                </el-button>
                <el-button type="primary" plain :loading="actionLoading" @click="handleUpdateGame">
                  <el-icon><Download /></el-icon>
                  更新服务器
                </el-button>
              </template>
            </template>
            <template v-else-if="instanceInfo.status === 'stopped'">
              <el-button type="success" :loading="actionLoading" @click="handleStart">
                <el-icon><VideoPlay /></el-icon>
                启动
              </el-button>
            </template>
            <template v-else-if="instanceInfo.status === 'error'">
              <el-button type="warning" :loading="actionLoading" @click="handleRestart">
                <el-icon><RefreshRight /></el-icon>
                重启
              </el-button>
            </template>
            <template v-else>
              <el-tag type="warning" effect="dark">{{ instanceInfo.runStatusDesc }}</el-tag>
            </template>
          </div>
        </div>
      </div>
      <div class="instance-signal-band" aria-label="实例运行上下文">
        <div class="signal-cell">
          <span>RUNTIME ID</span>
          <strong class="mono-text">{{ runtimeMetadata?.containerId || instanceInfo.id || '-' }}</strong>
        </div>
        <div class="signal-cell">
          <span>HOST / PORT</span>
          <strong>{{ instanceInfo.hostName }} · {{ instanceInfo.ip }}:{{ instanceInfo.port }}</strong>
        </div>
        <div class="signal-cell signal-cell--wide">
          <span>WORK DIR</span>
          <strong class="mono-text">{{ runtimeMetadata?.workDir || instanceInfo.deployPath || '-' }}</strong>
        </div>
        <div class="signal-cell">
          <span>CREATED</span>
          <strong>{{ instanceInfo.createdAt || '-' }}</strong>
        </div>
      </div>
    </el-card>
    
    <!-- Tab内容 -->
    <el-card class="content-card" shadow="never">
      <el-tabs v-model="activeTab" tab-position="left" class="workbench-tabs">
        <!-- 基础信息 -->
        <el-tab-pane name="info">
          <template #label>
            <span><el-icon><InfoFilled /></el-icon> 基础信息</span>
          </template>
          <div class="tab-content overview-workbench">
            <div class="tab-spotlight tab-spotlight--overview">
              <div>
                <span class="tab-spotlight__kicker">OPERATIONS SNAPSHOT</span>
                <h3>实例运行概览</h3>
                <p>从静态配置、实时资源到最近事件，快速判断当前实例是否需要处置。</p>
              </div>
              <span class="tab-spotlight__signal"><i class="status-dot is-online" />{{ instanceInfo.status === 'running' ? 'LIVE TELEMETRY' : 'TELEMETRY PAUSED' }}</span>
            </div>
            <div class="overview-layout">
              <section class="overview-panel summary-panel">
                <div class="panel-heading">
                  <div>
                    <span class="panel-kicker">INSTANCE</span>
                    <h3>基本信息</h3>
                  </div>
                  <el-tag size="small" effect="plain" type="info">静态配置</el-tag>
                </div>

                <el-descriptions class="details-descriptions" :column="2" border>
              <el-descriptions-item label="实例名称">
                <el-icon><Document /></el-icon>
                {{ instanceInfo.name }}
              </el-descriptions-item>
              <el-descriptions-item label="游戏">
                <el-icon><Grid /></el-icon>
                {{ instanceInfo.game }}
              </el-descriptions-item>
              <el-descriptions-item label="部署类型">
                <el-tag
                  :type="getDeployTypeTagType(instanceInfo.deployType)"
                  size="small"
                  effect="plain"
                >
                  <el-icon style="margin-right: 4px; vertical-align: middle"><Cpu /></el-icon>
                  {{ getDeployTypeText(instanceInfo.deployType) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="主机">
                <el-icon><Monitor /></el-icon>
                {{ instanceInfo.hostName }}
              </el-descriptions-item>
              <el-descriptions-item label="IP:端口">
                <el-icon><Link /></el-icon>
                {{ instanceInfo.ip }}:{{ instanceInfo.port }}
              </el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag :type="statusType(instanceInfo.status)" size="small">
                  {{ instanceInfo.runStatusDesc }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="玩家数">
                <el-icon><User /></el-icon>
                {{ instanceInfo.players || 0 }} / {{ instanceInfo.maxPlayers || 0 }}
              </el-descriptions-item>
              <el-descriptions-item label="CPU使用">
                <div class="metrics-item">
                  <template v-if="instanceInfo.status === 'running'">
                    <!-- 首次加载时显示"加载中..."，定时刷新时保持旧值显示 -->
                    <template v-if="metricsLoading && !hasMetrics">
                      <span class="text-muted">加载中...</span>
                    </template>
                    <template v-else>
                      <el-progress
                        :percentage="Math.round(instanceInfo.cpu || 0)"
                        :stroke-width="10"
                        style="width: 180px"
                      />
                      <span style="margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary)">
                        {{ hasMetrics ? instanceInfo.cpu.toFixed(2) + '%' : '-' }}
                      </span>
                    </template>
                  </template>
                  <span v-else class="text-muted">未运行</span>
                </div>
              </el-descriptions-item>
              <el-descriptions-item label="内存使用">
                <div class="metrics-item">
                  <template v-if="instanceInfo.status === 'running'">
                    <template v-if="metricsLoading && !hasMetrics">
                      <span class="text-muted">加载中...</span>
                    </template>
                    <template v-else>
                      <el-progress
                        :percentage="Math.round(instanceInfo.memory || 0)"
                        :stroke-width="10"
                        style="width: 180px"
                      />
                      <span style="margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary)">
                        {{ instanceInfo.memoryUsageText || (hasMetrics ? instanceInfo.memory.toFixed(2) + '%' : '-') }}
                      </span>
                    </template>
                  </template>
                  <span v-else class="text-muted">未运行</span>
                </div>
              </el-descriptions-item>
              <el-descriptions-item label="运行时间">
                <div class="metrics-item">
                  <template v-if="instanceInfo.status === 'running'">
                    <template v-if="metricsLoading && !hasMetrics">
                      <span class="text-muted">加载中...</span>
                    </template>
                    <template v-else>
                      <el-icon><Timer /></el-icon>
                      {{ hasMetrics ? formatUptime(instanceInfo.uptime) : '-' }}
                    </template>
                  </template>
                  <span v-else class="text-muted">未运行</span>
                </div>
              </el-descriptions-item>
              <el-descriptions-item label="创建时间">
                <el-icon><Calendar /></el-icon>
                {{ instanceInfo.createdAt }}
              </el-descriptions-item>
              <el-descriptions-item label="部署路径" :span="2">
                <el-icon><Folder /></el-icon>
                {{ instanceInfo.deployPath || '-' }}
              </el-descriptions-item>
                </el-descriptions>

            <!-- 运行时元数据（docker-compose 部署信息） -->
            <div v-if="runtimeMetadata" class="runtime-metadata-section">
              <div class="section-title">
                <el-icon><Box /></el-icon>
                运行时元数据
                <el-tag
                  size="small"
                  :type="getDeployTypeTagType(instanceInfo.deployType)"
                  effect="plain"
                  style="margin-left: 8px"
                >
                  {{ getDeployTypeText(instanceInfo.deployType) }}
                </el-tag>
              </div>
              <el-descriptions :column="2" border>
                <el-descriptions-item label="容器ID">
                  <span class="mono-text">{{ runtimeMetadata.containerId || '-' }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="Compose 项目名">
                  <span class="mono-text">{{ runtimeMetadata.projectName || '-' }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="工作目录" :span="2">
                  <span class="mono-text">{{ runtimeMetadata.workDir || '-' }}</span>
                </el-descriptions-item>
                <el-descriptions-item
                  v-if="runtimeMetadata.generatedAt"
                  label="元数据生成时间"
                  :span="2"
                >
                  {{ runtimeMetadata.generatedAt }}
                </el-descriptions-item>
              </el-descriptions>

              <!-- 命名卷宿主路径 -->
              <div v-if="volumePathList.length > 0" class="volume-paths">
                <div class="volume-title">
                  <el-icon><FolderOpened /></el-icon>
                  数据卷宿主路径
                </div>
                <el-table :data="volumePathList" size="small" border>
                  <el-table-column prop="name" label="卷名称" width="200">
                    <template #default="{ row }">
                      <span class="mono-text">{{ row.name }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="path" label="宿主机路径">
                    <template #default="{ row }">
                      <span class="mono-text">{{ row.path }}</span>
                      <el-button
                        link
                        size="small"
                        style="margin-left: 8px"
                        @click="copyToClipboard(row.path)"
                      >
                        <el-icon><CopyDocument /></el-icon>
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
              </section>

              <section class="overview-panel resource-panel">
                <div class="panel-heading">
                  <div>
                    <span class="panel-kicker">RUNTIME</span>
                    <h3>资源监控</h3>
                  </div>
                  <span class="refresh-state">
                    <i class="status-dot" :class="{ 'is-online': instanceInfo.status === 'running' }" />
                    {{ instanceInfo.status === 'running' ? '实时' : '未运行' }}
                  </span>
                </div>

                <div class="resource-list">
                  <div class="resource-row">
                    <div class="resource-label"><span>CPU 使用率</span><strong>{{ hasMetrics ? `${Math.round(instanceInfo.cpu || 0)}%` : '-' }}</strong></div>
                    <el-progress :percentage="Math.round(instanceInfo.cpu || 0)" :show-text="false" :stroke-width="6" />
                    <span class="resource-meta">{{ instanceInfo.status === 'running' ? '动态采样' : '实例未运行' }}</span>
                  </div>
                  <div class="resource-row">
                    <div class="resource-label"><span>内存使用率</span><strong>{{ hasMetrics ? (instanceInfo.memoryUsageText || `${Math.round(instanceInfo.memory || 0)}%`) : '-' }}</strong></div>
                    <el-progress :percentage="Math.round(instanceInfo.memory || 0)" :show-text="false" :stroke-width="6" status="success" />
                    <span class="resource-meta">{{ instanceInfo.status === 'running' ? '动态采样' : '实例未运行' }}</span>
                  </div>
                  <div class="resource-row uptime-row">
                    <div class="resource-label"><span>运行时间</span><strong>{{ instanceInfo.status === 'running' && hasMetrics ? formatUptime(instanceInfo.uptime) : '-' }}</strong></div>
                    <div class="uptime-track">
                      <span v-if="instanceInfo.status === 'running'" class="uptime-pulse" />
                    </div>
                    <span class="resource-meta">每 15 秒刷新</span>
                  </div>
                </div>

                <div v-if="metricsError" class="metrics-warning">
                  <el-icon><WarningFilled /></el-icon>
                  {{ metricsError }}
                </div>
              </section>
            </div>

            <div class="overview-lower-grid">
              <section class="overview-panel players-panel">
                <div class="panel-heading">
                  <div>
                    <span class="panel-kicker">PLAYERS</span>
                    <h3>在线玩家 <em>({{ instanceInfo.players || 0 }})</em></h3>
                  </div>
                  <span class="panel-muted">上限 {{ instanceInfo.maxPlayers || 0 }}</span>
                </div>
                <div class="player-summary">
                  <div class="player-count">{{ instanceInfo.players || 0 }}<small>/{{ instanceInfo.maxPlayers || 0 }}</small></div>
                  <div class="player-track"><span :style="{ width: `${Math.min(100, instanceInfo.maxPlayers ? (instanceInfo.players / instanceInfo.maxPlayers) * 100 : 0)}%` }" /></div>
                  <p>当前实例在线玩家</p>
                </div>
              </section>

              <section class="overview-panel recent-log-panel" @click="activeTab = 'logs'">
                <div class="panel-heading">
                  <div>
                    <span class="panel-kicker">EVENT STREAM</span>
                    <h3>实时日志</h3>
                  </div>
                  <button class="panel-link" type="button" @click.stop="activeTab = 'logs'">查看全部 <el-icon><ArrowRight /></el-icon></button>
                </div>
                <div v-if="recentLogs.length" class="recent-log-list">
                  <div v-for="(log, index) in recentLogs" :key="index" class="recent-log-line" :class="log.level">
                    <span class="recent-log-time">{{ log.time }}</span>
                    <span class="recent-log-message">{{ log.message }}</span>
                  </div>
                </div>
                <div v-else class="recent-log-empty">
                  <el-icon><Tickets /></el-icon>
                  <span>切换到日志查看以开启实时日志流</span>
                </div>
              </section>
            </div>
          </div>
        </el-tab-pane>
        
        <!-- 配置管理 -->
        <el-tab-pane name="config">
          <template #label>
            <span><el-icon><Setting /></el-icon> 配置管理</span>
          </template>
          <div class="tab-content config-workbench" v-loading="configLoading">
            <div class="tab-spotlight tab-spotlight--config">
              <div>
                <span class="tab-spotlight__kicker">CONFIGURATION WORKBENCH</span>
                <h3>配置编排</h3>
                <p>在结构化表单与原始文件之间切换，保存前确认当前作用域。</p>
              </div>
              <span class="tab-spotlight__meta"><el-icon><Document /></el-icon>{{ configFileName || '等待选择配置文件' }}</span>
            </div>
            <div class="config-header">
              <div class="config-left">
                <el-radio-group v-model="configMode" size="small">
                  <el-radio-button value="form">
                    <el-icon><List /></el-icon>
                    表单模式
                  </el-radio-button>
                  <el-radio-button value="editor">
                    <el-icon><Edit /></el-icon>
                    编辑器模式
                  </el-radio-button>
                </el-radio-group>
                <el-select v-model="configFileName" size="small" style="width: 200px; margin-left: 12px;">
                  <el-option 
                    v-for="file in configFiles" 
                    :key="file" 
                    :label="file" 
                    :value="file" 
                  />
                </el-select>
              </div>
              <el-button type="primary" :loading="configSaving" @click="saveConfig">
                <el-icon><Check /></el-icon>
                保存配置
              </el-button>
            </div>
            
            <div v-if="configMode === 'form'" class="config-form">
              <el-alert
                title="表单模式"
                description="部分配置项可能无法在表单中显示，如需编辑完整配置请切换到编辑器模式。"
                type="info"
                show-icon
                :closable="false"
                style="margin-bottom: 16px;"
              />
              <el-form :model="configForm" label-width="220px" class="config-form-inner">
                <el-form-item
                  v-for="(value, key) in configForm"
                  :key="key"
                  :label="key"
                >
                  <el-input v-model="configForm[key]" />
                </el-form-item>
              </el-form>
            </div>
            
            <div v-else class="config-editor">
              <el-alert
                title="编辑器模式"
                description="直接编辑配置文件内容，保存后将立即生效。"
                type="warning"
                show-icon
                :closable="false"
                style="margin-bottom: 16px;"
              />
              <el-input
                v-model="configEditorContent"
                type="textarea"
                :rows="20"
                placeholder="配置文件内容"
                class="config-textarea"
                spellcheck="false"
              />
            </div>
          </div>
        </el-tab-pane>
        
        <!-- 文件管理 -->
        <el-tab-pane name="files">
          <template #label>
            <span><el-icon><Folder /></el-icon> 文件管理</span>
          </template>
          <div class="tab-content file-workbench">
            <div class="tab-spotlight tab-spotlight--files">
              <div>
                <span class="tab-spotlight__kicker">FILESYSTEM EXPLORER</span>
                <h3>文件系统</h3>
                <p>以路径为主线浏览实例数据，常用操作保持在当前目录的操作带内。</p>
              </div>
              <span class="tab-spotlight__meta"><el-icon><FolderOpened /></el-icon>{{ currentPath || '/' }}</span>
            </div>
            <div class="file-header">
              <el-breadcrumb separator="/">
                <el-breadcrumb-item>
                  <el-button link @click="fetchFiles('/')">
                    <el-icon><HomeFilled /></el-icon>
                    根目录
                  </el-button>
                </el-breadcrumb-item>
                <el-breadcrumb-item v-for="(part, index) in currentPath.split('/').filter(Boolean)" :key="index">
                  <el-button link @click="navigateToPath(index)">{{ part }}</el-button>
                </el-breadcrumb-item>
              </el-breadcrumb>
              <div class="file-actions">
                <el-button size="small" :disabled="currentPath === '/'" @click="goBackPath">
                  <el-icon><ArrowUp /></el-icon>
                  上级目录
                </el-button>
                <el-button size="small" @click="createDirDialogVisible = true">
                  <el-icon><FolderAdd /></el-icon>
                  新建目录
                </el-button>
                <el-button size="small" type="primary" @click="uploadDialogVisible = true">
                  <el-icon><Upload /></el-icon>
                  上传文件
                </el-button>
              </div>
            </div>
            
            <el-table :data="fileList" v-loading="fileLoading" style="width: 100%">
              <el-table-column label="名称" min-width="300">
                <template #default="{ row }">
                  <div class="file-name" @click="handleFileClick(row)">
                    <el-icon :size="20" class="file-icon">
                      <Folder v-if="row.isDirectory" class="folder-icon" />
                      <Document v-else />
                    </el-icon>
                    <span>{{ row.name }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="size" label="大小" width="120">
                <template #default="{ row }">
                  {{ row.isDirectory ? '-' : formatFileSize(row.size) }}
                </template>
              </el-table-column>
              <el-table-column label="修改时间" width="180">
                <template #default="{ row }">
                  {{ formatFileTime(row.lastModified) }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="200">
                <template #default="{ row }">
                  <el-button v-if="!row.isDirectory" type="primary" link size="small" @click.stop="handleFileDownload(row)">
                    <el-icon><Download /></el-icon>
                    下载
                  </el-button>
                  <el-button type="danger" link size="small" @click.stop="handleFileDelete(row)">
                    <el-icon><Delete /></el-icon>
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
        
        <!-- 日志查看 -->
        <el-tab-pane name="logs">
          <template #label>
            <span><el-icon><Tickets /></el-icon> 日志查看</span>
          </template>
          <div class="tab-content log-workbench">
            <div class="tab-spotlight tab-spotlight--logs">
              <div>
                <span class="tab-spotlight__kicker">LOG STREAM</span>
                <h3>日志流</h3>
                <p>把筛选、刷新与事件正文放进同一条阅读路径，优先定位异常与上下文。</p>
              </div>
              <span class="tab-spotlight__meta"><i class="status-dot" :class="{ 'is-online': logsAutoRefresh }" />{{ logsAutoRefresh ? 'LIVE STREAM' : 'PAUSED' }}</span>
            </div>
            <div class="log-header">
              <div class="log-filters">
                <el-input
                  v-model="logsSearchKeyword"
                  placeholder="搜索日志..."
                  size="small"
                  style="width: 200px;"
                  @keyup.enter="handleLogsSearch"
                >
                  <template #suffix>
                    <el-icon @click="handleLogsSearch" class="search-icon"><Search /></el-icon>
                  </template>
                </el-input>
                <el-select v-model="logsLevelFilter" placeholder="日志级别" size="small" style="width: 120px; margin-left: 8px;">
                  <el-option label="全部" value="" />
                  <el-option label="信息" value="info" />
                  <el-option label="警告" value="warning" />
                  <el-option label="错误" value="error" />
                </el-select>
              </div>
              <div class="log-actions">
                <el-button size="small" @click="fetchLogs">
                  <el-icon><Refresh /></el-icon>
                  刷新
                </el-button>
                <el-button 
                  size="small" 
                  :type="logsAutoRefresh ? 'primary' : 'default'"
                  @click="toggleAutoRefresh"
                >
                  <el-icon><Timer /></el-icon>
                  自动刷新 {{ logsAutoRefresh ? '开' : '关' }}
                </el-button>
                <el-button size="small" @click="handleLogsDownload">
                  <el-icon><Download /></el-icon>
                  下载日志
                </el-button>
              </div>
            </div>
            
            <div ref="logsContainerRef" class="log-content" v-loading="logsLoading">
              <div v-for="(log, index) in logs" :key="index" class="log-line" :class="log.level">
                <span class="log-time">{{ log.time }}</span>
                <el-tag :type="log.level === 'error' ? 'danger' : log.level === 'warning' ? 'warning' : 'info'" size="small" effect="plain">
                  {{ log.level?.toUpperCase() }}
                </el-tag>
                <span class="log-message">{{ log.message }}</span>
              </div>
              <el-empty v-if="logs.length === 0" description="暂无日志" />
            </div>
          </div>
        </el-tab-pane>
        
        <!-- 控制台 -->
        <el-tab-pane name="console">
          <template #label>
            <span><el-icon><Monitor /></el-icon> 控制台</span>
          </template>
          <div class="tab-content console-tab console-workbench">
            <div class="tab-spotlight tab-spotlight--console">
              <div>
                <span class="tab-spotlight__kicker">COMMAND TERMINAL</span>
                <h3>运行控制台</h3>
                <p>连接状态、命令历史与输入提示保持在同一终端上下文中。</p>
              </div>
              <span class="tab-spotlight__meta"><i class="status-dot" :class="{ 'is-online': consoleConnected }" />{{ consoleConnected ? 'CONNECTED' : consoleConnecting ? 'CONNECTING' : 'OFFLINE' }}</span>
            </div>
            <div class="console-toolbar">
              <el-tag :type="consoleConnected ? 'success' : 'danger'" size="small" effect="dark">
                <el-icon v-if="consoleConnecting" class="is-loading"><Loading /></el-icon>
                <el-icon v-else><component :is="consoleConnected ? 'CircleCheck' : 'CircleClose'" /></el-icon>
                {{ consoleConnected ? '已连接' : consoleConnecting ? '连接中...' : '未连接' }}
              </el-tag>
              <el-button size="small" @click="clearConsole">
                <el-icon><Delete /></el-icon>
                清屏
              </el-button>
            </div>
            <div ref="consoleContainerRef" class="console-output">
              <div v-for="(item, index) in consoleHistory" :key="index" class="console-line" :class="item.type">
                <span class="console-time">[{{ item.time }}]</span>
                <span class="console-content">{{ item.content }}</span>
              </div>
              <div v-if="consoleHistory.length === 0" class="console-empty">
                <el-empty description="等待命令输入..." />
              </div>
            </div>
            <div class="console-input">
              <el-input
                v-model="consoleInput"
                placeholder="输入命令..."
                @keyup.enter="sendCommand"
                :disabled="!consoleConnected"
              >
                <template #prefix>
                  <span class="console-prompt">&gt;</span>
                </template>
              </el-input>
              <el-button type="primary" @click="sendCommand" :disabled="!consoleConnected">
                <el-icon><Promotion /></el-icon>
                发送
              </el-button>
            </div>
          </div>
        </el-tab-pane>
        
        <!-- 备份还原 -->
        <el-tab-pane name="backup">
          <template #label>
            <span><el-icon><Download /></el-icon> 备份还原</span>
          </template>
          <div class="tab-content backup-workbench">
            <div class="tab-spotlight tab-spotlight--backup">
              <div>
                <span class="tab-spotlight__kicker">BACKUP VAULT</span>
                <h3>备份与还原</h3>
                <p>将快照、活动任务和恢复动作组织在一处，降低高风险操作的确认成本。</p>
              </div>
              <span class="tab-spotlight__meta"><el-icon><Lock /></el-icon>{{ backupStore.hasActiveBackup || backupStore.hasActiveRestore ? 'TASK IN PROGRESS' : 'VAULT READY' }}</span>
            </div>
            <!-- 活动进度显示 -->
            <div v-if="backupStore.hasActiveBackup || backupStore.hasActiveRestore" class="active-progress-section">
              <BackupProgress
                v-if="backupStore.hasActiveBackup"
                :progress="activeBackupProgress.progress"
                :status="activeBackupProgress.status"
                :message="activeBackupProgress.message"
                :current-step="activeBackupProgress.currentStep"
                :completed="activeBackupProgress.completed"
                @cancel="handleCancelBackup({ id: activeBackupProgress.backupId, name: '当前备份' })"
              />
              <BackupProgress
                v-if="backupStore.hasActiveRestore"
                :progress="activeRestoreProgress.progress"
                :status="activeRestoreProgress.status"
                :message="activeRestoreProgress.message"
                :current-step="activeRestoreProgress.currentStep"
                :completed="activeRestoreProgress.completed"
                :show-cancel="false"
              />
            </div>

            <!-- 操作栏 -->
            <div class="backup-header">
              <div class="backup-filters">
                <el-select
                  v-model="backupTypeFilter"
                  placeholder="备份类型"
                  size="small"
                  clearable
                  style="width: 120px"
                  @change="handleBackupFilterChange"
                >
                  <el-option label="数据库" value="database" />
                  <el-option label="文件" value="files" />
                </el-select>
                <el-select
                  v-model="backupStatusFilter"
                  placeholder="备份状态"
                  size="small"
                  clearable
                  style="width: 120px; margin-left: 8px;"
                  @change="handleBackupFilterChange"
                >
                  <el-option label="备份中" value="running" />
                  <el-option label="成功" value="completed" />
                  <el-option label="失败" value="failed" />
                </el-select>
              </div>
              <div class="backup-actions">
                <el-button
                  v-if="selectedBackups.length > 0"
                  type="danger"
                  size="small"
                  @click="handleBatchDelete"
                >
                  <el-icon><Delete /></el-icon>
                  批量删除 ({{ selectedBackups.length }})
                </el-button>
                <el-button type="primary" size="small" @click="backupFormVisible = true">
                  <el-icon><Plus /></el-icon>
                  创建备份
                </el-button>
              </div>
            </div>

            <!-- 备份列表 -->
            <el-table
              :data="backupList"
              v-loading="backupLoading"
              style="width: 100%"
              @selection-change="handleBackupSelectionChange"
            >
              <el-table-column type="selection" width="55" />
              <el-table-column prop="name" label="备份名称" min-width="180">
                <template #default="{ row }">
                  <div class="backup-name-cell">
                    <el-icon :size="16">
                      <DataLine v-if="row.type === 'database'" />
                      <Document v-else-if="row.type === 'files'" />
                      <Files v-else />
                    </el-icon>
                    <span>{{ row.name }}</span>
                    <el-tag
                      v-if="row.status === 'running'"
                      type="warning"
                      size="small"
                      effect="plain"
                      class="status-badge"
                    >
                      <el-icon class="is-loading"><Loading /></el-icon>
                      备份中
                    </el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="type" label="类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.type === 'database' ? 'primary' : 'success'">
                    {{ getBackupTypeText(row.type) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="size" label="大小" width="100">
                <template #default="{ row }">
                  {{ formatFileSize(row.size) }}
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="getBackupStatusType(row.status)" size="small">
                    {{ getBackupStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="description" label="描述" min-width="150" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="备份时间" width="160">
                <template #default="{ row }">
                  {{ row.createdAt ? new Date(row.createdAt).toLocaleString('zh-CN') : '-' }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="220" fixed="right">
                <template #default="{ row }">
                  <!-- 还原按钮 -->
                  <el-button
                    v-if="row.status === 'completed'"
                    type="primary"
                    link
                    size="small"
                    :disabled="backupStore.hasActiveRestore"
                    @click="handleRestore(row)"
                  >
                    <el-icon><RefreshLeft /></el-icon>
                    还原
                  </el-button>

                  <!-- 取消按钮 -->
                  <el-button
                    v-if="row.status === 'running'"
                    type="warning"
                    link
                    size="small"
                    @click="handleCancelBackup(row)"
                  >
                    <el-icon><CircleClose /></el-icon>
                    取消
                  </el-button>

                  <!-- 下载按钮 -->
                  <el-button
                    v-if="row.status === 'completed'"
                    type="success"
                    link
                    size="small"
                    @click="handleDownloadBackup(row)"
                  >
                    <el-icon><Download /></el-icon>
                    下载
                  </el-button>

                  <!-- 验证按钮 -->
                  <el-button
                    v-if="row.status === 'completed'"
                    type="info"
                    link
                    size="small"
                    :loading="verifyingBackup === row.id"
                    @click="handleVerifyBackup(row)"
                  >
                    <el-icon><CircleCheck /></el-icon>
                    验证
                  </el-button>

                  <!-- 删除按钮 -->
                  <el-button
                    type="danger"
                    link
                    size="small"
                    :disabled="row.status === 'running'"
                    @click="handleDeleteBackup(row)"
                  >
                    <el-icon><Delete /></el-icon>
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <!-- 分页 -->
            <div class="backup-pagination">
              <el-pagination
                v-model:current-page="backupPagination.current"
                v-model:page-size="backupPagination.size"
                :page-sizes="[10, 20, 50, 100]"
                :total="backupPagination.total"
                layout="total, sizes, prev, pager, next, jumper"
                @size-change="handleBackupSizeChange"
                @current-change="handleBackupPageChange"
              />
            </div>
          </div>
        </el-tab-pane>
        
        <!-- 插件扩展 -->
        <el-tab-pane name="plugin">
          <template #label>
            <span><el-icon><Connection /></el-icon> 插件扩展</span>
          </template>
          <div class="tab-content plugin-tab-content plugin-workbench">
            <div class="tab-spotlight tab-spotlight--plugin">
              <div>
                <span class="tab-spotlight__kicker">PLUGIN WORKSPACE</span>
                <h3>游戏扩展工作区</h3>
                <p>插件能力在实例上下文中运行，主应用负责承载边界与运行时身份。</p>
              </div>
              <span class="tab-spotlight__meta"><el-icon><Connection /></el-icon>{{ instanceInfo.gameCode || '等待游戏识别' }}</span>
            </div>
            <PluginTab
              v-if="instanceInfo.gameCode"
              :instance-id="Number(instanceId)"
              :game-code="instanceInfo.gameCode"
              :instance-name="instanceInfo.name"
              :host-id="Number(instanceInfo.hostId)"
              :host-ip="instanceInfo.ip"
              :deploy-path="instanceInfo.deployPath"
              :ports="{ game: Number(instanceInfo.port) }"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    
    <!-- 上传文件对话框 -->
    <el-dialog v-model="uploadDialogVisible" title="上传文件" width="500px">
      <el-upload
        v-model:file-list="uploadFileList"
        action="#"
        :auto-upload="false"
        :multiple="true"
        drag
        style="width: 100%;"
      >
        <el-icon class="el-icon--upload" :size="50"><UploadFilled /></el-icon>
        <div class="el-upload__text">
          将文件拖到此处，或<em>点击上传</em>
        </div>
      </el-upload>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="fileUploading" @click="handleFileUpload">上传</el-button>
      </template>
    </el-dialog>
    
    <!-- 创建目录对话框 -->
    <el-dialog v-model="createDirDialogVisible" title="新建目录" width="400px">
      <el-form>
        <el-form-item label="目录名称">
          <el-input v-model="newDirName" placeholder="请输入目录名称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDirDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreateDirectory">创建</el-button>
      </template>
    </el-dialog>
    
    <!-- 文件编辑器对话框 -->
    <el-dialog v-model="fileEditorVisible" :title="`编辑: ${editingFile.name}`" width="800px" top="5vh">
      <el-input
        v-model="editingFile.content"
        type="textarea"
        :rows="25"
        class="file-editor"
        spellcheck="false"
      />
      <template #footer>
        <el-button @click="fileEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="fileEditorSaving" @click="saveFileContent">保存</el-button>
      </template>
    </el-dialog>
    
    <!-- 创建备份对话框 -->
    <BackupForm
      v-model:visible="backupFormVisible"
      :instance-id="instanceId"
      :loading="creatingBackup"
      @submit="handleCreateBackup"
      @cancel="backupFormVisible = false"
    />

    <!-- 还原确认对话框 -->
    <RestoreConfirm
      v-model:visible="restoreDialogVisible"
      :backup="currentRestoreBackup"
      :instance="instanceInfo"
      :loading="restoringBackup"
      :restore-progress="activeRestoreProgress"
      @confirm="handleConfirmRestore"
      @cancel="restoreDialogVisible = false"
    />
  </div>
</template>

<style lang="scss" scoped>
.instance-detail-container {
  min-width: 0;

  .info-card {
    margin-bottom: 12px;
    background: var(--platform-surface-1);
    border-color: var(--platform-line);

    :deep(.el-card__body) {
      padding: 14px 18px;
    }
  }

  .metrics-item {
    min-height: 24px;
    min-width: 80px;
    display: inline-flex;
    align-items: center;
  }

  .text-muted {
    color: var(--el-text-color-placeholder);
    font-size: 12px;
  }

  .content-card {
    background: var(--platform-surface-1);
    border-color: var(--platform-line);

    :deep(.el-card__body) {
      padding: 0;
    }
    
    :deep(.el-tabs__content) {
      padding: 16px;
    }
  }
}

.command-identity {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 9px;
  border-bottom: 1px solid rgba(91, 135, 154, 0.16);
}

.command-kicker,
.command-channel,
.command-hint,
.signal-cell span {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.command-kicker {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: #86b4c5;
}

.command-channel {
  margin-left: 7px;
  color: #5e8291;
}

.command-hint {
  color: #6b8c99;
  white-space: nowrap;
}

.live-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border: 1px solid rgba(140, 169, 179, 0.65);
  border-radius: 50%;
  background: transparent;
}

.live-dot.is-live {
  border-color: var(--platform-green);
  background: var(--platform-green);
  box-shadow: 0 0 0 4px rgba(48, 207, 116, 0.11), 0 0 13px rgba(48, 207, 116, 0.65);
}

.instance-header {
  padding: 14px 0 15px;
  border-bottom: 1px solid rgba(91, 135, 154, 0.16);
}

.instance-signal-band {
  display: grid;
  grid-template-columns: 0.8fr 0.8fr 1.7fr 0.9fr;
  margin-top: 13px;
  border: 1px solid rgba(83, 132, 163, 0.24);
  background: rgba(8, 26, 39, 0.42);
}

.signal-cell {
  display: grid;
  min-width: 0;
  gap: 5px;
  padding: 10px 13px;
  border-right: 1px solid rgba(91, 135, 154, 0.15);
}

.signal-cell:last-child { border-right: 0; }

.signal-cell strong {
  overflow: hidden;
  color: #d8eaf0;
  font-size: 12px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.signal-cell .mono-text {
  color: #8bc8dc;
  font-family: var(--el-font-family-mono);
  font-size: 11px;
}

.signal-cell--wide strong { color: #9ebcc7; }

.instance-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  
  .header-left {
    display: flex;
    align-items: center;
    gap: 14px;
    flex-wrap: wrap;
  }
  
  .instance-title {
    display: flex;
    align-items: center;
    gap: 12px;
    
    h2 {
      margin: 0;
      color: var(--el-text-color-primary);
      font-size: 20px;
      font-weight: var(--platform-font-weight-bold);
    }
  }
  
  .instance-meta {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: var(--platform-font-size-sm);
    color: var(--el-text-color-secondary);
    
    .el-icon {
      margin-right: 2px;
    }
  }
  
  .header-right {
    display: flex;
    gap: 8px;
  }
}

.command-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 17px 0 18px;
}

.command-header .header-left {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 13px;
}

.back-button {
  flex: 0 0 auto;
  color: #83c9df !important;
}

.back-button:hover { color: #d9f7ff !important; }

.instance-mark {
  display: grid;
  width: 50px;
  height: 50px;
  flex: 0 0 50px;
  place-items: center;
  border: 1px solid rgba(74, 191, 231, 0.42);
  border-radius: 14px;
  color: #95e4fb;
  background: linear-gradient(145deg, rgba(29, 104, 132, 0.75), rgba(16, 56, 75, 0.75));
  box-shadow: 0 0 26px rgba(22, 180, 230, 0.12);
}

.command-header .instance-title {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.title-kicker,
.command-state__label,
.stat-label,
.action-label {
  color: #6d94a2;
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.title-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.title-line h1 {
  overflow: hidden;
  margin: 0;
  color: #ecf7fa;
  font-size: 24px;
  font-weight: 620;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command-header .instance-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 13px;
  color: #8baab6;
  font-size: 11px;
}

.command-header .instance-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.command-header .instance-meta .el-icon { color: #6faec1; }

.command-state {
  display: grid;
  min-width: 190px;
  gap: 4px;
  padding: 10px 14px;
  border-left: 1px solid rgba(91, 135, 154, 0.25);
}

.command-state strong {
  color: var(--platform-green);
  font-family: var(--el-font-family-mono);
  font-size: 18px;
  font-weight: 500;
  letter-spacing: 0.08em;
}

.command-state small {
  color: #7f9da7;
  font-size: 11px;
}

.command-dashboard {
  display: grid;
  grid-template-columns: 1.05fr 0.9fr 1.08fr 0.95fr 1.4fr;
  border: 1px solid rgba(83, 132, 163, 0.25);
  background: rgba(8, 26, 39, 0.46);
}

.command-stat,
.command-action-rail {
  display: grid;
  min-width: 0;
  align-content: center;
  gap: 6px;
  min-height: 86px;
  padding: 13px 15px;
  border-right: 1px solid rgba(91, 135, 154, 0.16);
}

.command-stat > strong {
  overflow: hidden;
  color: #e1f0f4;
  font-family: var(--el-font-family-mono);
  font-size: 20px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command-stat > strong small {
  color: #7896a2;
  font-size: 12px;
}

.command-stat--players > strong { color: #95e5f8; }

.command-progress { width: 100%; }

.command-progress :deep(.el-progress-bar__outer) { background: rgba(2, 15, 25, 0.78); }
.command-progress :deep(.el-progress-bar__inner) { background: #42bfe9; }
.command-progress :deep(.el-progress-bar__inner.el-progress-bar__inner--success) { background: #48d18d; }

.stat-note { color: #6f8d98; font-size: 10px; }

.command-action-rail {
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-right: 0;
  background: linear-gradient(100deg, rgba(20, 65, 80, 0.12), rgba(31, 78, 94, 0.3));
}

.command-action-rail .header-right {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.command-action-rail .el-button { min-width: 70px; }

.header-metrics {
  display: flex;
  align-items: stretch;
  gap: 0;
  margin-left: auto;
  margin-right: 12px;
  border-left: 1px solid var(--platform-line);
}

.header-metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 76px;
  padding: 0 14px;
  border-right: 1px solid var(--platform-line);

  span {
    color: var(--el-text-color-secondary);
    font-size: 11px;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 14px;
    font-weight: 600;
    white-space: nowrap;
  }
}

.workbench-tabs {
  min-height: 580px;
  background: var(--platform-surface-1) !important;
  border: 1px solid var(--platform-line) !important;

  :deep(.el-tabs__header) {
    width: 174px;
    margin: 0;
    padding: 14px 10px;
    background: linear-gradient(180deg, rgba(12, 34, 48, 0.96), rgba(9, 24, 37, 0.96)) !important;
    border-right: 1px solid var(--platform-line) !important;
  }

  :deep(.el-tabs__nav-wrap) { padding: 0; }
  :deep(.el-tabs__nav-wrap::after) { display: none; }
  :deep(.el-tabs__nav) { width: 100%; border: 0; }
  :deep(.el-tabs__active-bar) { display: none; }

  :deep(.el-tabs__item) {
    display: flex;
    height: 43px;
    align-items: center;
    justify-content: flex-start;
    margin: 3px 0;
    padding: 0 12px;
    border: 1px solid transparent;
    border-radius: 5px;
    color: var(--platform-text-secondary) !important;
    font-size: 12px;
    transition: background 160ms ease, border-color 160ms ease, color 160ms ease;
  }

  :deep(.el-tabs__item .el-icon) {
    margin-right: 9px;
    color: #7094a1;
  }

  :deep(.el-tabs__item.is-active) {
    color: #b9efff !important;
    background: rgba(22, 105, 131, 0.3) !important;
    border-color: rgba(44, 180, 221, 0.42);
    box-shadow: inset 2px 0 0 #32c9f4;
  }

  :deep(.el-tabs__item.is-active .el-icon) { color: #67dcfa; }
  :deep(.el-tabs__item:hover) { color: #d3f6fd !important; background: rgba(22, 82, 104, 0.26); }

  :deep(.el-tabs__content) {
    min-width: 0;
    padding: 21px;
    background: var(--platform-surface-1) !important;
    color: var(--platform-text-regular);
  }
}

.tab-content {
  min-height: 400px;
}

.overview-layout,
.overview-lower-grid {
  display: grid;
  gap: 12px;
}

.overview-layout {
  grid-template-columns: minmax(0, 1.18fr) minmax(320px, 0.82fr);
}

.overview-lower-grid {
  grid-template-columns: minmax(220px, 0.52fr) minmax(0, 1.48fr);
  margin-top: 12px;
}

.overview-panel {
  min-width: 0;
  padding: 16px;
  background: var(--platform-surface-2);
  border: 1px solid var(--platform-line);
  border-radius: 5px;
}

.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  min-height: 34px;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--platform-line);

  h3 {
    margin: 2px 0 0;
    color: var(--el-text-color-primary);
    font-size: 15px;
    font-weight: 600;

    em {
      color: var(--el-text-color-secondary);
      font-size: 12px;
      font-style: normal;
      font-weight: 400;
    }
  }
}

.panel-kicker {
  color: var(--el-text-color-disabled);
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.12em;
}

.panel-muted,
.refresh-state {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.refresh-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  background: var(--platform-green);
  border-radius: 50%;

  &.is-online {
    box-shadow: 0 0 0 3px rgba(82, 207, 130, 0.12);
  }
}

.details-descriptions {
  :deep(.el-descriptions__label),
  :deep(.el-descriptions__content) {
    padding: 10px 12px;
  }

  :deep(.el-descriptions__label) {
    width: 108px;
    white-space: nowrap;
  }
}

.resource-list {
  display: flex;
  flex-direction: column;
  gap: 22px;
  padding: 8px 2px 4px;
}

.resource-row {
  .resource-label {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
    color: var(--el-text-color-secondary);
    font-size: 13px;

    strong {
      color: var(--platform-cyan);
      font-size: 17px;
      font-weight: 600;
    }
  }

  :deep(.el-progress-bar__outer) {
    background: var(--platform-surface-0);
  }

  :deep(.el-progress-bar__inner) {
    background: var(--platform-cyan);
  }

  .resource-meta {
    display: block;
    margin-top: 5px;
    color: var(--el-text-color-disabled);
    font-size: 11px;
  }
}

.uptime-track,
.player-track {
  height: 6px;
  overflow: hidden;
  background: var(--platform-surface-0);
  border-radius: 999px;

  > span {
    display: block;
    height: 100%;
    background: var(--platform-green);
    border-radius: inherit;
    transition: width var(--transition-duration);
  }
}

.uptime-track {
  position: relative;
  background: linear-gradient(90deg, var(--platform-surface-0), var(--platform-surface-2));

  .uptime-pulse {
    position: absolute;
    top: 50%;
    left: 18%;
    width: 8px;
    height: 8px;
    background: var(--platform-green);
    border: 2px solid var(--platform-surface-1);
    border-radius: 50%;
    box-shadow: 0 0 0 3px rgba(82, 207, 130, 0.14), 0 0 12px rgba(82, 207, 130, 0.58);
    transform: translate(-50%, -50%);
  }
}

.metrics-warning {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 18px;
  padding: 9px 10px;
  color: var(--platform-amber);
  font-size: 12px;
  background: rgba(242, 184, 75, 0.08);
  border: 1px solid rgba(242, 184, 75, 0.25);
  border-radius: 4px;
}

.player-summary {
  padding: 10px 2px 4px;

  .player-count {
    color: var(--el-text-color-primary);
    font-size: 32px;
    font-weight: 600;

    small {
      margin-left: 4px;
      color: var(--el-text-color-secondary);
      font-size: 14px;
      font-weight: 400;
    }
  }

  .player-track {
    margin: 16px 0 10px;

    span {
      background: var(--platform-cyan);
    }
  }

  p {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.recent-log-panel {
  cursor: pointer;
  transition: border-color var(--transition-duration-fast), background-color var(--transition-duration-fast);

  &:hover {
    background: var(--platform-surface-3);
    border-color: rgba(39, 181, 243, 0.48);
  }
}

.panel-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 0;
  color: var(--platform-cyan);
  font-size: 12px;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.recent-log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 122px;
  overflow: hidden;
  font-family: var(--el-font-family-mono);
  font-size: 11px;
}

.recent-log-line {
  display: flex;
  gap: 10px;
  min-width: 0;

  .recent-log-time {
    flex-shrink: 0;
    color: var(--el-text-color-disabled);
  }

  .recent-log-message {
    overflow: hidden;
    color: var(--el-text-color-regular);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &.error .recent-log-message { color: var(--platform-red); }
  &.warning .recent-log-message { color: var(--platform-amber); }
}

.recent-log-empty {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 72px;
  color: var(--el-text-color-disabled);
  font-size: 12px;
}

// 插件 Tab
.plugin-tab-content {
  min-height: 500px;
  height: calc(100vh - 320px);
  padding: 0;
}

// 运行时元数据
.runtime-metadata-section {
  margin-top: 24px;
  padding: 16px 20px;
  background: var(--el-fill-color-light);
  border-radius: var(--border-radius-base);

  .section-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: var(--platform-font-weight-medium);
    color: var(--el-text-color-primary);
    margin-bottom: 16px;
    font-size: var(--platform-font-size-base);

    .el-icon {
      color: var(--el-color-primary);
    }
  }

  .mono-text {
    font-family: var(--el-font-family-mono);
    font-size: var(--platform-font-size-sm);
    word-break: break-all;
  }

  .volume-paths {
    margin-top: 16px;

    .volume-title {
      display: flex;
      align-items: center;
      gap: 6px;
      font-weight: var(--platform-font-weight-medium);
      color: var(--el-text-color-primary);
      margin-bottom: 8px;
      font-size: var(--platform-font-size-sm);

      .el-icon {
        color: var(--el-color-warning);
      }
    }
  }
}

// 配置管理
.config-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  
  .config-left {
    display: flex;
    align-items: center;
  }
}

.config-form {
  max-width: 800px;
}

.config-form-inner {
  :deep(.el-form-item__label) {
    height: auto;
    min-height: 32px;
    line-height: 32px;
    white-space: normal;
    word-break: break-word;
    padding: 0 12px 0 0;
    display: inline-flex;
    align-items: center;
  }
}

.config-editor {
  .config-textarea {
    font-family: var(--el-font-family-mono);
    font-size: 13px;
  }
}

// 文件管理
.file-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
  
  .file-actions {
    display: flex;
    gap: 8px;
  }
}

.file-name {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  
  &:hover {
    color: var(--el-color-primary);
  }
  
  .file-icon {
    color: var(--el-text-color-secondary);
    
    &.folder-icon {
      color: var(--el-color-warning);
    }
  }
}

// 日志查看
.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
  
  .log-filters {
    display: flex;
    align-items: center;
    
    .search-icon {
      cursor: pointer;
      color: var(--el-text-color-secondary);
      
      &:hover {
        color: var(--el-color-primary);
      }
    }
  }
  
  .log-actions {
    display: flex;
    gap: 8px;
  }
}

.log-content {
  background: #1e1e1e;
  border-radius: var(--border-radius-base);
  padding: 16px;
  max-height: 500px;
  overflow-y: auto;
  font-family: var(--el-font-family-mono);
  font-size: 13px;
}

.log-line {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 4px;
  padding: 2px 0;
  color: #c9d1d9;
  
  &.error {
    color: #f85149;
  }
  
  &.warning {
    color: #d29922;
  }
  
  &.info {
    color: #58a6ff;
  }
  
  .log-time {
    color: #6e7681;
    flex-shrink: 0;
    min-width: 64px;
  }
  
  .log-message {
    word-break: break-all;
    white-space: pre-wrap; /* 保留原始换行（多行日志条目） */
    flex: 1;
  }
  
  .el-tag {
    flex-shrink: 0;
    min-width: 50px;
    text-align: center;
  }
}

// 控制台
.console-tab {
  display: flex;
  flex-direction: column;
  height: 500px;
}

.console-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.console-output {
  flex: 1;
  background: #1e1e1e;
  border-radius: var(--border-radius-base);
  padding: 16px;
  overflow-y: auto;
  font-family: var(--el-font-family-mono);
  font-size: 13px;
  margin-bottom: 12px;
}

.console-line {
  margin-bottom: 4px;
  color: #c9d1d9;
  
  .console-time {
    color: #6e7681;
    margin-right: 8px;
  }

  .console-content {
    white-space: pre-wrap; /* 保留 \r\n 换行（ps 等多行输出按行展示） */
    word-break: break-all;
  }
  
  &.input {
    color: #7ee787;
    
    &::before {
      content: '> ';
      color: var(--el-color-success);
    }
  }
  
  &.output {
    color: #c9d1d9;
  }
  
  &.error {
    color: #f85149;
  }
  
  &.system {
    color: #58a6ff;
    font-style: italic;
  }
}

.console-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.console-input {
  display: flex;
  gap: 8px;
  
  .console-prompt {
    color: var(--el-color-success);
    font-weight: bold;
  }
}

// 备份还原
.backup-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;

  .backup-filters {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .backup-actions {
    display: flex;
    gap: 8px;
  }
}

.active-progress-section {
  margin-bottom: 16px;

  .backup-progress {
    margin-bottom: 12px;

    &:last-child {
      margin-bottom: 0;
    }
  }
}

.backup-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;

  .el-icon {
    color: var(--el-text-color-secondary);
    flex-shrink: 0;
  }

  span {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .status-badge {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 4px;

    .el-icon {
      color: inherit;
    }
  }
}

.backup-pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

// 文件编辑器
.file-editor {
  :deep(.el-textarea__inner) {
    font-family: var(--el-font-family-mono);
    font-size: 13px;
    line-height: 1.6;
  }
}

// 滚动条样式
.log-content, .console-output {
  &::-webkit-scrollbar {
    width: 8px;
    height: 8px;
  }
  
  &::-webkit-scrollbar-track {
    background: #1e1e1e;
  }
  
  &::-webkit-scrollbar-thumb {
    background: #484f58;
    border-radius: 4px;
  }
  
  &::-webkit-scrollbar-thumb:hover {
    background: #6e7681;
  }
}

// 子工作区视觉语言
.content-card :deep(.workbench-tabs .el-tabs__content) {
  padding: 21px;
}

.tab-spotlight {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 74px;
  margin-bottom: 16px;
  padding: 15px 18px;
  background: linear-gradient(110deg, rgba(24, 45, 59, 0.9), rgba(14, 28, 39, 0.94));
  border: 1px solid rgba(91, 135, 154, 0.24);
  border-left: 3px solid var(--platform-cyan);
  border-radius: 4px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.025);

  h3 {
    margin: 5px 0 4px;
    color: var(--el-text-color-primary);
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 0.01em;
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.6;
  }
}

.tab-spotlight__kicker {
  color: var(--platform-cyan);
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.16em;
}

.tab-spotlight__meta,
.tab-spotlight__signal {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  gap: 7px;
  min-height: 28px;
  padding: 0 10px;
  color: var(--el-text-color-secondary);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.04em;
  background: rgba(6, 15, 23, 0.46);
  border: 1px solid rgba(91, 135, 154, 0.24);
  border-radius: 3px;
}

.tab-spotlight__meta .el-icon,
.tab-spotlight__signal .el-icon {
  color: var(--platform-cyan);
}

.tab-spotlight__meta .status-dot:not(.is-online) {
  background: var(--el-text-color-disabled);
  box-shadow: none;
}

.tab-spotlight--overview { border-left-color: var(--platform-green); }
.tab-spotlight--overview .tab-spotlight__kicker { color: var(--platform-green); }
.tab-spotlight--config { border-left-color: #b68cff; }
.tab-spotlight--config .tab-spotlight__kicker { color: #c5a7ff; }
.tab-spotlight--files { border-left-color: var(--platform-amber); }
.tab-spotlight--files .tab-spotlight__kicker { color: var(--platform-amber); }
.tab-spotlight--logs { border-left-color: var(--platform-cyan); }
.tab-spotlight--console { border-left-color: var(--platform-green); }
.tab-spotlight--console .tab-spotlight__kicker { color: var(--platform-green); }
.tab-spotlight--backup { border-left-color: #c792ff; }
.tab-spotlight--backup .tab-spotlight__kicker { color: #d3adff; }
.tab-spotlight--plugin { border-left-color: #66d9ff; }

.config-workbench {
  .config-header {
    min-height: 54px;
    margin-bottom: 14px;
    padding: 10px 12px;
    background: rgba(17, 34, 46, 0.72);
    border: 1px solid var(--platform-line);
    border-radius: 4px;
  }

  .config-form,
  .config-editor {
    max-width: none;
    padding: 16px;
    background: rgba(18, 34, 46, 0.72);
    border: 1px solid var(--platform-line);
    border-radius: 4px;
  }

  .config-form-inner {
    max-width: 860px;
    padding-top: 6px;
  }

  :deep(.config-textarea .el-textarea__inner) {
    min-height: 390px !important;
    padding: 15px 16px;
    color: #bfe7ef;
    line-height: 1.7;
    background: #08131c;
    border-color: rgba(91, 135, 154, 0.3);
  }

  :deep(.el-alert) {
    background: rgba(16, 33, 45, 0.86);
    border-color: rgba(91, 135, 154, 0.22);
  }
}

.file-workbench {
  .file-header {
    min-height: 54px;
    margin-bottom: 14px;
    padding: 10px 12px;
    background: rgba(17, 34, 46, 0.72);
    border: 1px solid var(--platform-line);
    border-radius: 4px;
  }

  :deep(.el-table) {
    background: rgba(11, 24, 34, 0.72);
    border: 1px solid var(--platform-line);
  }

  :deep(.el-table__header-wrapper th) {
    color: var(--el-text-color-secondary);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.05em;
    background: rgba(24, 45, 59, 0.88);
  }

  :deep(.el-table__row) {
    transition: background-color var(--transition-duration-fast);
  }

  :deep(.el-table__row:hover > td) {
    background: rgba(25, 68, 86, 0.24) !important;
  }

  .file-name {
    font-family: var(--el-font-family-mono);
    font-size: 12px;
  }
}

.log-workbench {
  .log-header {
    min-height: 54px;
    margin-bottom: 14px;
    padding: 10px 12px;
    background: rgba(17, 34, 46, 0.72);
    border: 1px solid var(--platform-line);
    border-radius: 4px;
  }

  .log-content {
    min-height: 420px;
    padding: 16px 18px;
    background: #071118;
    border: 1px solid rgba(91, 135, 154, 0.3);
    border-radius: 4px;
    box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.18);
  }

  .log-line {
    position: relative;
    margin-bottom: 0;
    padding: 6px 0 6px 12px;
    border-bottom: 1px solid rgba(91, 135, 154, 0.1);

    &::before {
      position: absolute;
      top: 0;
      bottom: 0;
      left: 0;
      width: 2px;
      background: rgba(91, 135, 154, 0.3);
      content: '';
    }

    &.error::before { background: var(--platform-red); }
    &.warning::before { background: var(--platform-amber); }
    &.info::before { background: var(--platform-cyan); }
  }
}

.console-workbench {
  .console-toolbar {
    min-height: 42px;
    margin-bottom: 10px;
    padding: 7px 10px;
    background: rgba(17, 34, 46, 0.72);
    border: 1px solid var(--platform-line);
    border-radius: 4px;
  }

  .console-output {
    min-height: 350px;
    padding: 18px;
    background: #050b10;
    border: 1px solid rgba(82, 207, 130, 0.24);
    border-radius: 4px;
    box-shadow: inset 0 0 28px rgba(0, 0, 0, 0.24);
  }

  .console-line {
    padding: 4px 0;
    line-height: 1.55;
  }

  .console-input {
    align-items: center;
    margin-top: 2px;
    padding: 8px 10px;
    background: rgba(17, 34, 46, 0.78);
    border: 1px solid var(--platform-line);
    border-radius: 4px;
  }

  :deep(.console-input .el-input__wrapper) {
    background: #08131c;
    box-shadow: none;
  }
}

.backup-workbench {
  .active-progress-section {
    margin-bottom: 14px;
    padding: 12px;
    background: rgba(49, 39, 20, 0.22);
    border: 1px solid rgba(242, 184, 75, 0.28);
    border-radius: 4px;
  }

  .backup-header {
    min-height: 54px;
    margin-bottom: 14px;
    padding: 10px 12px;
    background: rgba(17, 34, 46, 0.72);
    border: 1px solid var(--platform-line);
    border-radius: 4px;
  }

  :deep(.el-table) {
    background: rgba(11, 24, 34, 0.72);
    border: 1px solid var(--platform-line);
  }

  :deep(.el-table__header-wrapper th) {
    color: var(--el-text-color-secondary);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.05em;
    background: rgba(24, 45, 59, 0.88);
  }
}

.plugin-workbench {
  .tab-spotlight {
    margin-bottom: 12px;
  }

  :deep(.plugin-tab) {
    min-height: 410px;
    padding: 14px;
    background: rgba(8, 19, 28, 0.62);
    border: 1px solid rgba(102, 217, 255, 0.22);
    border-radius: 4px;
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .command-identity {
    align-items: flex-start;
    flex-direction: column;
    gap: 7px;
  }

  .command-hint { padding-left: 16px; }

  .instance-signal-band {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .signal-cell:nth-child(2) { border-right: 0; }
  .signal-cell--wide { grid-column: 1 / -1; border-top: 1px solid rgba(91, 135, 154, 0.15); }

  .command-header {
    flex-direction: column;
    align-items: flex-start;
    
    .header-left {
      flex-direction: column;
      align-items: flex-start;
    }
    
    .header-right {
      width: 100%;
      
      .el-button {
        flex: 1;
      }
    }
  }

  .command-state {
    width: 100%;
    min-width: 0;
    padding: 10px 0 0;
    border-top: 1px solid rgba(91, 135, 154, 0.2);
    border-left: 0;
  }

  .command-dashboard { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .command-action-rail { grid-column: 1 / -1; border-top: 1px solid rgba(91, 135, 154, 0.16); }
  .workbench-tabs :deep(.el-tabs__header) { width: 146px; }
  
  .config-header {
    flex-direction: column;
    align-items: flex-start;
    
    .config-left {
      flex-direction: column;
      align-items: flex-start;
      gap: 12px;
    }
  }
  
  .file-header {
    flex-direction: column;
    align-items: flex-start;
  }
  
  .log-header {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
