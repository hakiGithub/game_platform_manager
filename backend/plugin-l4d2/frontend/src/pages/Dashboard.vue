<template>
  <div class="dashboard-page">
    <!-- Hero 头部 -->
    <div class="plugin-page-header dashboard-hero">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / SERVER STATUS</span>
        <h2>仪表盘</h2>
        <p>实例运行状态、玩家概况与快捷运维入口</p>
      </div>
      <div class="header-actions">
        <span class="hero-state">
          <span class="status-dot" :class="heroDotClass"></span>
          {{ statusText }}
        </span>
        <el-button type="primary" @click="refreshStatus" :loading="loading">
          <el-icon><Refresh /></el-icon>
          刷新状态
        </el-button>
      </div>
    </div>

    <!-- 指标卡片 -->
    <div class="metric-grid">
      <div class="metric-card">
        <div class="metric-card-header">
          <span class="section-kicker">INSTANCE STATE</span>
          <el-icon :size="18" class="metric-icon" :class="statusClass">
            <component :is="statusIcon" />
          </el-icon>
        </div>
        <div class="metric-value" :class="statusClass">{{ statusText }}</div>
        <div class="metric-state">
          <span class="status-dot" :class="heroDotClass"></span>
          <span>实例 {{ instanceStatus?.runStatus === 1 ? '在线' : '离线' }}</span>
          <span class="metric-link" @click="$router.push('/server-info')">详情</span>
        </div>
      </div>

      <div class="metric-card">
        <div class="metric-card-header">
          <span class="section-kicker">PLAYERS ONLINE</span>
          <el-icon :size="18" class="metric-icon players"><User /></el-icon>
        </div>
        <div class="metric-value">{{ serverStatus?.players || 0 }}<span class="metric-sub"> / {{ serverStatus?.maxPlayers || 8 }}</span></div>
        <div class="metric-state">
          <span class="status-dot" :class="(serverStatus?.players ?? 0) > 0 ? 'running' : 'stopped'"></span>
          <span>{{ (serverStatus?.players ?? 0) > 0 ? '有玩家在线' : '空闲' }}</span>
        </div>
      </div>

      <div class="metric-card">
        <div class="metric-card-header">
          <span class="section-kicker">CURRENT MAP</span>
          <el-icon :size="18" class="metric-icon map"><Map /></el-icon>
        </div>
        <div class="metric-value metric-value-text">{{ currentMapName }}</div>
        <div class="metric-state">
          <span class="status-dot" :class="serverStatus?.map ? 'running' : 'stopped'"></span>
          <span>{{ serverStatus?.map ? '运行中地图' : '未获取' }}</span>
          <span class="metric-link" @click="$router.push('/maps')">地图管理</span>
        </div>
      </div>

      <div class="metric-card">
        <div class="metric-card-header">
          <span class="section-kicker">RCON LINK</span>
          <el-icon :size="18" class="metric-icon" :class="rconStatusClass === 'online' ? 'online' : 'offline'">
            <Connection />
          </el-icon>
        </div>
        <div class="metric-value" :class="rconStatusClass === 'online' ? 'online' : 'offline'">{{ rconStatusText }}</div>
        <div class="metric-state">
          <span class="status-dot" :class="rconStatusClass === 'online' ? 'running' : 'stopped'"></span>
          <span>{{ rconStatusClass === 'online' ? '控制通道可用' : '控制通道不可用' }}</span>
          <span class="metric-link" @click="$router.push('/rcon')">控制台</span>
        </div>
      </div>
    </div>

    <!-- 快速操作 -->
    <el-card class="workspace-card" shadow="never">
      <template #header>
        <div class="workspace-header">
          <div class="header-copy">
            <span class="section-kicker">LIFECYCLE</span>
            <h3>快速操作</h3>
          </div>
          <span class="header-context">实例 {{ isRunning ? '运行中' : '已停止' }}</span>
        </div>
      </template>
      <div class="action-grid">
        <el-button type="success" size="large" class="action-btn" @click="startServer"
          :disabled="isRunning" :loading="actionLoading.start">
          <el-icon :size="18"><VideoPlay /></el-icon>
          <span>启动服务器</span>
        </el-button>
        <el-button type="danger" size="large" class="action-btn" @click="stopServer"
          :disabled="!isRunning" :loading="actionLoading.stop">
          <el-icon :size="18"><VideoPause /></el-icon>
          <span>停止服务器</span>
        </el-button>
        <el-button type="warning" size="large" class="action-btn" @click="restartServer"
          :disabled="!isRunning" :loading="actionLoading.restart">
          <el-icon :size="18"><RefreshRight /></el-icon>
          <span>重启服务器</span>
        </el-button>
        <el-button size="large" class="action-btn" @click="updateServer" :loading="actionLoading.update">
          <el-icon :size="18"><Download /></el-icon>
          <span>更新服务器</span>
        </el-button>
      </div>
    </el-card>

    <!-- 服务器详情 -->
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card class="workspace-card detail-card" shadow="never">
          <template #header>
            <div class="workspace-header">
              <div class="header-copy">
                <span class="section-kicker">GAME SETTINGS</span>
                <h3>游戏设置</h3>
              </div>
            </div>
          </template>
          <div class="info-list">
            <div class="info-item">
              <span class="info-label">难度</span>
              <el-tag :color="difficultyColor" effect="dark" size="small" class="difficulty-tag">
                {{ difficultyText }}
              </el-tag>
            </div>
            <div class="info-item">
              <span class="info-label">游戏模式</span>
              <el-tag type="info" size="small">{{ gameModeText }}</el-tag>
            </div>
            <div class="info-item">
              <span class="info-label">运行时间</span>
              <span class="info-value">{{ formatUptime(serverStatus?.uptime || 0) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">服务器版本</span>
              <span class="info-value">{{ serverStatus?.version || '-' }}</span>
            </div>
            <div class="info-item" v-if="serverStatus?.osType">
              <span class="info-label">操作系统</span>
              <span class="info-value">{{ serverStatus.osType }}</span>
            </div>
            <div class="info-item" v-if="serverStatus?.serverType">
              <span class="info-label">服务器类型</span>
              <span class="info-value">{{ serverStatus.serverType }}</span>
            </div>
            <div class="info-item" v-if="!serverStatus?.online && serverStatus?.reason">
              <span class="info-label">离线原因</span>
              <span class="info-value error-text">{{ serverStatus.reason }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card class="workspace-card detail-card" shadow="never">
          <template #header>
            <div class="workspace-header">
              <div class="header-copy">
                <span class="section-kicker">SHORTCUTS</span>
                <h3>快捷功能</h3>
              </div>
            </div>
          </template>
          <div class="quick-links">
            <div class="quick-link" @click="$router.push('/maps')">
              <div class="quick-link-icon map"><el-icon :size="20"><Map /></el-icon></div>
              <span class="quick-link-label">地图管理</span>
            </div>
            <div class="quick-link" @click="$router.push('/plugins')">
              <div class="quick-link-icon plugin"><el-icon :size="20"><Box /></el-icon></div>
              <span class="quick-link-label">插件管理</span>
            </div>
            <div class="quick-link" @click="$router.push('/rcon')">
              <div class="quick-link-icon rcon"><el-icon :size="20"><Monitor /></el-icon></div>
              <span class="quick-link-label">控制台</span>
            </div>
            <div class="quick-link" @click="$router.push('/monitor')">
              <div class="quick-link-icon monitor"><el-icon :size="20"><TrendCharts /></el-icon></div>
              <span class="quick-link-label">性能监控</span>
            </div>
            <div class="quick-link" @click="$router.push('/admins')">
              <div class="quick-link-icon admin"><el-icon :size="20"><User /></el-icon></div>
              <span class="quick-link-label">管理员</span>
            </div>
            <div class="quick-link" @click="$router.push('/server-config')">
              <div class="quick-link-icon config"><el-icon :size="20"><Setting /></el-icon></div>
              <span class="quick-link-label">服务器配置</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { serverApi, instanceApi } from '@/api'
import type { InstanceStatusVO } from '@/api'
import { usePluginStore } from '@/stores/plugin'
import { DIFFICULTIES, GAME_MODES } from '@/utils/gameConstants'
import { formatUptime, parseMapName } from '@/utils/statusParser'
import type { ServerStatus } from '@/types'

const pluginStore = usePluginStore()
const loading = ref(false)
const serverStatus = ref<ServerStatus | null>(null)
/** 实例运行状态（来自核心 InstanceSyncService 同步），用于显示服务器运行状态 */
const instanceStatus = ref<InstanceStatusVO | null>(null)
/** 实例不存在标志，触发自动跳转到实例选择页 */
const instanceNotFound = ref(false)

const actionLoading = ref({
  start: false,
  stop: false,
  restart: false,
  update: false
})

// 计算属性：实例状态（来自核心同步），即使 RCON 不可达也能正确显示
const statusClass = computed(() => {
  const rs = instanceStatus.value?.runStatus
  if (rs === 1) return 'online'
  if (rs === 2) return 'abnormal'
  return 'offline'
})

const statusIcon = computed(() => {
  const rs = instanceStatus.value?.runStatus
  if (rs === 1) return 'CircleCheck'
  if (rs === 2) return 'Warning'
  return 'CircleClose'
})

const statusText = computed(() => {
  if (!instanceStatus.value) return '加载中...'
  return instanceStatus.value.runStatusDesc || '未知'
})

/** hero 状态点：running=运行，deploying=启停中，stopped=停止，error=异常 */
const heroDotClass = computed(() => {
  const rs = instanceStatus.value?.runStatus
  if (rs === 1) return 'running'
  if (rs === 2) return 'error'
  if (rs === 5 || rs === 6 || rs === 7) return 'deploying'
  return 'stopped'
})

/** 实例是否运行中，用于控制按钮启用状态 */
const isRunning = computed(() => instanceStatus.value?.runStatus === 1)

// RCON 详情状态（仅当实例运行中时才尝试查询）
const rconStatusClass = computed(() => {
  if (!isRunning.value) return 'offline'
  return serverStatus.value?.online ? 'online' : 'offline'
})

const rconStatusText = computed(() => {
  if (!isRunning.value) return '未运行'
  return serverStatus.value?.online ? '已连接' : '未连接'
})

const currentMapName = computed(() => {
  if (!serverStatus.value?.map) return '-'
  const parsed = parseMapName(serverStatus.value.map)
  return parsed.displayName
})

const difficultyText = computed(() => {
  const difficulty = serverStatus.value?.difficulty || 'normal'
  return DIFFICULTIES[difficulty as keyof typeof DIFFICULTIES]?.label || '普通'
})

const difficultyColor = computed(() => {
  const difficulty = serverStatus.value?.difficulty || 'normal'
  return DIFFICULTIES[difficulty as keyof typeof DIFFICULTIES]?.color || 'var(--platform-cyan)'
})

const gameModeText = computed(() => {
  const mode = serverStatus.value?.gameMode || 'coop'
  return GAME_MODES[mode as keyof typeof GAME_MODES]?.label || '合作模式'
})

/**
 * 刷新实例运行状态（来自核心 InstanceSyncService 维护的 runStatus）
 */
async function refreshInstanceStatus() {
  const instanceId = pluginStore.instanceInfo?.instanceId
  if (!instanceId) {
    instanceNotFound.value = true
    return
  }
  try {
    const data = await instanceApi.getStatus(instanceId)
    instanceStatus.value = data
    instanceNotFound.value = false
  } catch (error: any) {
    // 实例不存在时清除选择
    if (error?.message?.includes('不存在') || error?.message?.includes('not found')) {
      instanceNotFound.value = true
      pluginStore.clearInstance()
    } else {
      // 其他错误（网络/权限）保留上次状态
      console.warn('[Dashboard] refreshInstanceStatus failed:', error)
    }
  }
}

/**
 * 刷新 RCON 详细信息（玩家/地图/难度等），仅在实例运行中时查询
 */
async function refreshRconStatus() {
  if (!isRunning.value) {
    serverStatus.value = null
    return
  }
  const instanceId = pluginStore.instanceInfo?.instanceId
  if (!instanceId) return
  try {
    serverStatus.value = await serverApi.getStatus(instanceId)
  } catch (error) {
    // RCON 不可达（端口未暴露/防火墙等）时静默，状态卡片已显示"未连接"
    console.warn('[Dashboard] RCON status failed:', error)
    serverStatus.value = null
  }
}

/**
 * 综合刷新：先刷新实例状态，再按需刷新 RCON 详情
 */
async function refreshStatus() {
  loading.value = true
  try {
    await refreshInstanceStatus()
    await refreshRconStatus()
  } finally {
    loading.value = false
  }
}

async function startServer() {
  const instanceId = pluginStore.instanceInfo?.instanceId
  if (!instanceId) {
    pluginStore.notifyError('启动失败', '未获取到实例信息')
    return
  }
  actionLoading.value.start = true
  try {
    await instanceApi.start(instanceId)
    pluginStore.notifySuccess('启动成功', '实例正在启动')
    // 立即置为启动中，2秒后查询实际状态
    if (instanceStatus.value) instanceStatus.value.runStatus = 6
    setTimeout(refreshStatus, 2000)
  } catch (error) {
    pluginStore.notifyError('启动失败', '实例启动失败')
  } finally {
    actionLoading.value.start = false
  }
}

async function stopServer() {
  const instanceId = pluginStore.instanceInfo?.instanceId
  if (!instanceId) {
    pluginStore.notifyError('停止失败', '未获取到实例信息')
    return
  }
  const confirmed = await pluginStore.confirm('确认停止', '确定要停止服务器吗？')
  if (!confirmed) return

  actionLoading.value.stop = true
  try {
    await instanceApi.stop(instanceId)
    pluginStore.notifySuccess('停止成功', '实例已停止')
    refreshStatus()
  } catch (error) {
    pluginStore.notifyError('停止失败', '实例停止失败')
  } finally {
    actionLoading.value.stop = false
  }
}

async function restartServer() {
  const instanceId = pluginStore.instanceInfo?.instanceId
  if (!instanceId) {
    pluginStore.notifyError('重启失败', '未获取到实例信息')
    return
  }
  const confirmed = await pluginStore.confirm('确认重启', '确定要重启服务器吗？')
  if (!confirmed) return

  actionLoading.value.restart = true
  try {
    await instanceApi.restart(instanceId)
    pluginStore.notifySuccess('重启成功', '实例正在重启')
    setTimeout(refreshStatus, 3000)
  } catch (error) {
    pluginStore.notifyError('重启失败', '实例重启失败')
  } finally {
    actionLoading.value.restart = false
  }
}

async function updateServer() {
  // 服务器更新走专用接口；原 /server/update 端点不存在，先保留按钮但提示
  pluginStore.notifyError('更新失败', '当前版本暂未提供服务器更新接口，请通过主机终端手动更新')
}

// 定时刷新
let refreshTimer: number | null = null

onMounted(() => {
  refreshStatus()
  // 每30秒刷新一次状态
  refreshTimer = window.setInterval(refreshStatus, 30000)
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
  }
})
</script>

<style lang="scss" scoped>
.dashboard-page {
  height: 100%;
  padding: 18px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dashboard-hero {
  .header-actions {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .hero-state {
    display: inline-flex;
    align-items: center;
    font-size: 13px;
    color: var(--platform-text-secondary);
  }
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px;
  background: var(--platform-surface-1);
  border: 1px solid var(--platform-line);
  border-radius: 6px;
  transition: border-color 0.16s, background-color 0.16s;

  &:hover {
    border-color: rgba(39, 181, 243, 0.45);
  }

  .metric-card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .metric-icon {
    &.online { color: var(--platform-status-running); }
    &.offline { color: var(--platform-status-stopped); }
    &.abnormal { color: var(--platform-status-deploying); }
    &.players { color: var(--platform-cyan); }
    &.map { color: var(--platform-amber); }
  }

  .metric-value {
    font-size: 24px;
    font-weight: 600;
    color: var(--platform-text-primary);
    line-height: 1.25;
    word-break: break-all;

    &.online { color: var(--platform-status-running); }
    &.offline { color: var(--platform-status-stopped); }
    &.abnormal { color: var(--platform-status-deploying); }

    .metric-sub {
      font-size: 14px;
      font-weight: 400;
      color: var(--platform-text-muted);
    }

    &.metric-value-text {
      font-size: 18px;
    }
  }

  .metric-state {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-top: auto;
    padding-top: 8px;
    border-top: 1px solid var(--platform-line);
    font-size: 12px;
    color: var(--platform-text-secondary);

    .metric-link {
      margin-left: auto;
      color: var(--platform-cyan);
      cursor: pointer;

      &:hover {
        text-decoration: underline;
      }
    }
  }
}

.detail-card {
  height: 100%;

  :deep(.el-card__body) {
    display: flex;
    flex-direction: column;

    .info-list,
    .quick-links {
      flex: 1;
    }
  }
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.action-btn {
  width: 100%;
  height: 46px;
  font-size: 14px;

  .el-icon {
    margin-right: 6px;
  }
}

.info-list {
  display: flex;
  flex-direction: column;

  .info-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 11px 0;
    border-bottom: 1px solid var(--platform-line);

    &:last-child {
      border-bottom: none;
    }

    .info-label {
      font-size: 13px;
      color: var(--platform-text-secondary);
    }

    .info-value {
      font-size: 13px;
      color: var(--platform-text-primary);
      font-weight: 500;

      &.error-text {
        color: var(--platform-status-error);
      }
    }
  }
}

.quick-links {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;

  .quick-link {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 104px;
    padding: 14px 8px;
    border: 1px solid var(--platform-line);
    border-radius: 6px;
    background: var(--platform-surface-2);
    cursor: pointer;
    transition: border-color 0.16s, transform 0.16s;

    &:hover {
      border-color: rgba(39, 181, 243, 0.45);
      transform: translateY(-2px);
    }

    .quick-link-icon {
      width: 40px;
      height: 40px;
      border-radius: 8px;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--platform-surface-3);

      &.map { color: var(--platform-cyan); }
      &.plugin { color: var(--platform-green); }
      &.rcon { color: var(--platform-amber); }
      &.monitor { color: var(--platform-red); }
      &.admin { color: var(--platform-text-secondary); }
      &.config { color: #c397f5; }
    }

    .quick-link-label {
      font-size: 13px;
      color: var(--platform-text-regular);
      font-weight: 500;
    }
  }
}

@media (max-width: 1200px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .action-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .metric-grid,
  .action-grid,
  .quick-links {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
