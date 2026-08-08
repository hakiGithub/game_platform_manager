<template>
  <div class="dashboard-page">
    <div class="page-header">
      <h1 class="page-title">仪表盘</h1>
      <div class="header-actions">
        <el-button type="primary" plain @click="refreshStatus" :loading="loading">
          <el-icon><Refresh /></el-icon>
          刷新状态
        </el-button>
      </div>
    </div>

      <!-- 服务器状态卡片 -->
      <el-row :gutter="20" class="status-cards">
        <el-col :span="6">
          <el-card shadow="hover" class="status-card">
            <div class="status-icon" :class="statusClass">
              <el-icon :size="32">
                <component :is="statusIcon" />
              </el-icon>
            </div>
            <div class="status-info">
              <div class="status-label">实例状态</div>
              <div class="status-value" :class="statusClass">
                {{ statusText }}
              </div>
            </div>
          </el-card>
        </el-col>

        <el-col :span="6">
          <el-card shadow="hover" class="status-card">
            <div class="status-icon players">
              <el-icon :size="32"><User /></el-icon>
            </div>
            <div class="status-info">
              <div class="status-label">在线玩家</div>
              <div class="status-value">
                {{ serverStatus?.players || 0 }} / {{ serverStatus?.maxPlayers || 8 }}
              </div>
            </div>
          </el-card>
        </el-col>

        <el-col :span="6">
          <el-card shadow="hover" class="status-card">
            <div class="status-icon map">
              <el-icon :size="32"><Map /></el-icon>
            </div>
            <div class="status-info">
              <div class="status-label">当前地图</div>
              <div class="status-value">
                {{ currentMapName }}
              </div>
            </div>
          </el-card>
        </el-col>

        <el-col :span="6">
          <el-card shadow="hover" class="status-card">
            <div class="status-icon fps">
              <el-icon :size="32"><TrendCharts /></el-icon>
            </div>
            <div class="status-info">
              <div class="status-label">RCON 连接</div>
              <div class="status-value" :class="rconStatusClass">
                {{ rconStatusText }}
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 快速操作 -->
      <el-card class="quick-actions" shadow="hover">
        <template #header>
          <div class="card-header">
            <span>快速操作</span>
          </div>
        </template>
        
        <div class="action-grid">
          <el-button
            type="success"
            size="large"
            plain
            class="action-btn"
            @click="startServer"
            :disabled="isRunning"
            :loading="actionLoading.start"
          >
            <el-icon :size="18"><VideoPlay /></el-icon>
            <span>启动服务器</span>
          </el-button>
          <el-button
            type="danger"
            size="large"
            plain
            class="action-btn"
            @click="stopServer"
            :disabled="!isRunning"
            :loading="actionLoading.stop"
          >
            <el-icon :size="18"><VideoPause /></el-icon>
            <span>停止服务器</span>
          </el-button>
          <el-button
            type="warning"
            size="large"
            plain
            class="action-btn"
            @click="restartServer"
            :disabled="!isRunning"
            :loading="actionLoading.restart"
          >
            <el-icon :size="18"><RefreshRight /></el-icon>
            <span>重启服务器</span>
          </el-button>
          <el-button
            type="primary"
            size="large"
            plain
            class="action-btn"
            @click="updateServer"
            :loading="actionLoading.update"
          >
            <el-icon :size="18"><Download /></el-icon>
            <span>更新服务器</span>
          </el-button>
        </div>
      </el-card>

      <!-- 服务器详情 -->
      <el-row :gutter="20">
        <el-col :span="12">
          <el-card shadow="hover">
            <template #header>
              <div class="card-header">
                <span>游戏设置</span>
              </div>
            </template>
            
            <div class="info-list">
              <div class="info-item">
                <span class="info-label">难度</span>
                <el-tag :color="difficultyColor" effect="dark" size="small">
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
                <span class="info-value" style="color: #f56c6c;">{{ serverStatus.reason }}</span>
              </div>
            </div>
          </el-card>
        </el-col>

        <el-col :span="12">
          <el-card shadow="hover">
            <template #header>
              <div class="card-header">
                <span>快捷功能</span>
              </div>
            </template>
            
            <div class="quick-links">
              <div class="quick-link" @click="$router.push('/maps')">
                <div class="quick-link-icon map">
                  <el-icon :size="22"><Map /></el-icon>
                </div>
                <span class="quick-link-label">地图管理</span>
              </div>
              <div class="quick-link" @click="$router.push('/plugins')">
                <div class="quick-link-icon plugin">
                  <el-icon :size="22"><Box /></el-icon>
                </div>
                <span class="quick-link-label">插件管理</span>
              </div>
              <div class="quick-link" @click="$router.push('/rcon')">
                <div class="quick-link-icon rcon">
                  <el-icon :size="22"><Monitor /></el-icon>
                </div>
                <span class="quick-link-label">控制台</span>
              </div>
              <div class="quick-link" @click="$router.push('/monitor')">
                <div class="quick-link-icon monitor">
                  <el-icon :size="22"><TrendCharts /></el-icon>
                </div>
                <span class="quick-link-label">性能监控</span>
              </div>
              <div class="quick-link" @click="$router.push('/admins')">
                <div class="quick-link-icon admin">
                  <el-icon :size="22"><User /></el-icon>
                </div>
                <span class="quick-link-label">管理员</span>
              </div>
              <div class="quick-link" @click="$router.push('/server-config')">
                <div class="quick-link-icon config">
                  <el-icon :size="22"><Setting /></el-icon>
                </div>
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
import { useRouter } from 'vue-router'
import { serverApi, instanceApi } from '@/api'
import type { InstanceStatusVO } from '@/api'
import { usePluginStore } from '@/stores/plugin'
import { DIFFICULTIES, GAME_MODES } from '@/utils/gameConstants'
import { formatUptime, parseMapName } from '@/utils/statusParser'
import type { ServerStatus } from '@/types'

const pluginStore = usePluginStore()
const router = useRouter()
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
  return DIFFICULTIES[difficulty as keyof typeof DIFFICULTIES]?.color || '#409eff'
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
  padding: 16px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;

  .page-title {
    margin: 0;
    font-size: 22px;
    font-weight: 600;
    color: var(--el-text-color-primary);
    line-height: 1.2;
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }
}

.status-cards {
  flex-shrink: 0;
  margin-bottom: 0;
}

.status-card {
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 108px;

  :deep(.el-card__body) {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 16px;
  }

  .status-icon {
    width: 56px;
    height: 56px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: white;
    flex-shrink: 0;

    &.online {
      background: linear-gradient(135deg, #67c23a 0%, #85ce61 100%);
    }

    &.offline {
      background: linear-gradient(135deg, #909399 0%, #b4b4b4 100%);
    }

    &.abnormal {
      background: linear-gradient(135deg, #e6a23c 0%, #f0c787 100%);
    }

    &.players {
      background: linear-gradient(135deg, #409eff 0%, #66b1ff 100%);
    }

    &.map {
      background: linear-gradient(135deg, #e6a23c 0%, #ebb563 100%);
    }

    &.fps {
      background: linear-gradient(135deg, #f56c6c 0%, #f78989 100%);
    }
  }

  .status-info {
    flex: 1;
    min-width: 0;

    .status-label {
      font-size: 13px;
      color: var(--el-text-color-secondary);
      margin-bottom: 6px;
    }

    .status-value {
      font-size: 20px;
      font-weight: 600;
      color: var(--el-text-color-primary);
      line-height: 1.3;
      word-break: break-all;

      &.online {
        color: #67c23a;
      }

      &.offline {
        color: #909399;
      }

      &.abnormal {
        color: #e6a23c;
      }

      &.empty {
        color: var(--el-text-color-placeholder);
      }
    }
  }
}

.quick-actions {
  flex-shrink: 0;

  :deep(.el-card__header) {
    padding: 14px 16px;
  }

  :deep(.el-card__body) {
    padding: 16px;
  }

  .card-header {
    font-size: 16px;
    font-weight: 600;
  }

  .action-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
  }

  .action-btn {
    width: 100%;
    height: 52px;
    font-size: 15px;
    border-radius: 8px;

    .el-icon {
      margin-right: 6px;
    }
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 16px;
  font-weight: 600;
}

.info-list {
  display: flex;
  flex-direction: column;
  gap: 14px;

  .info-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 0;
    border-bottom: 1px solid var(--el-border-color-lighter);

    &:last-child {
      border-bottom: none;
    }

    .info-label {
      font-size: 14px;
      color: var(--el-text-color-secondary);
    }

    .info-value {
      font-size: 14px;
      color: var(--el-text-color-primary);
      font-weight: 500;
    }
  }
}

.quick-links {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  justify-items: stretch;
  align-items: stretch;

  .quick-link {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    width: 100%;
    min-height: 110px;
    padding: 16px 8px;
    border-radius: 8px;
    border: 1px solid var(--el-border-color-lighter);
    background-color: var(--el-bg-color);
    box-sizing: border-box;
    cursor: pointer;
    transition: all 0.2s ease;

    &:hover {
      border-color: var(--el-color-primary-light-5);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
      transform: translateY(-2px);
    }

    .quick-link-icon {
      width: 44px;
      height: 44px;
      border-radius: 10px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: white;

      &.map { background: linear-gradient(135deg, #409eff 0%, #66b1ff 100%); }
      &.plugin { background: linear-gradient(135deg, #67c23a 0%, #85ce61 100%); }
      &.rcon { background: linear-gradient(135deg, #e6a23c 0%, #ebb563 100%); }
      &.monitor { background: linear-gradient(135deg, #f56c6c 0%, #f78989 100%); }
      &.admin { background: linear-gradient(135deg, #909399 0%, #b4b4b4 100%); }
      &.config { background: linear-gradient(135deg, #9c27b0 0%, #ba68c8 100%); }
    }

    .quick-link-label {
      font-size: 13px;
      color: var(--el-text-color-primary);
      font-weight: 500;
      text-align: center;
    }
  }
}

@media (max-width: 1200px) {
  .quick-actions .action-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .quick-actions .action-grid,
  .quick-links {
    grid-template-columns: repeat(2, 1fr);
  }

  .status-card {
    margin-bottom: 12px;
  }
}
</style>
