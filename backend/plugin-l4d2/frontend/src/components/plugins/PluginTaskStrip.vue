<template>
  <el-collapse-transition>
    <div v-if="visible" class="task-strip">
      <div class="strip-main">
        <div class="strip-info">
          <template v-if="builtin.active">
            <el-icon class="strip-icon spinning"><Loading /></el-icon>
            <span class="strip-title">{{ builtin.title || '插件安装中' }}</span>
            <el-progress
              :percentage="builtin.progress"
              :stroke-width="8"
              status="success"
              class="strip-progress"
            />
          </template>
          <template v-else-if="runningRemoteTasks.length">
            <el-icon class="strip-icon spinning"><Loading /></el-icon>
            <span class="strip-title">
              下载中 {{ runningRemoteTasks.length }} 个任务
            </span>
            <el-progress
              :percentage="remoteProgress"
              :stroke-width="8"
              class="strip-progress"
            />
            <span class="strip-bytes">
              {{ formatBytes(activeTask.downloadedBytes) }} / {{ formatBytes(activeTask.totalBytes) }}
            </span>
          </template>
          <template v-else>
            <el-icon color="var(--platform-green)"><CircleCheckFilled /></el-icon>
            <span class="strip-title">插件任务已全部完成</span>
          </template>
        </div>
        <el-button size="small" link @click="expanded = !expanded">
          {{ expanded ? '收起明细' : '展开明细' }}
          <el-icon><ArrowDown :class="{ flipped: expanded }" /></el-icon>
        </el-button>
      </div>

      <div v-if="expanded" class="strip-detail">
        <div v-if="!remoteTasks.length" class="detail-empty">暂无下载任务记录</div>
        <div v-for="t in remoteTasks" :key="t.taskId" class="detail-row">
          <span class="t-name">{{ t.filename || t.pluginId }}</span>
          <el-progress
            :percentage="taskPercentage(t)"
            :stroke-width="6"
            :status="progressStatus(t.status)"
            class="t-progress"
          />
          <el-tag :type="statusTagType(t.status)" size="small">{{ statusLabel(t.status) }}</el-tag>
          <el-button
            v-if="isRunningStatus(t.status)"
            type="danger"
            size="small"
            link
            @click="onCancelTask(t)"
          >
            取消
          </el-button>
        </div>
      </div>
    </div>
  </el-collapse-transition>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pluginStoreApi, STORE_TASK_RUNNING_STATUSES } from '@/api'

export interface StripRemoteTask {
  taskId: string
  pluginId: string
  status: string
  progress: number
  totalBytes: number
  downloadedBytes: number
  filename?: string
}

const props = defineProps<{
  /** 内置插件安装任务进度（单装/批装共用） */
  builtin: { active: boolean; progress: number; title: string }
  /** 远端仓库下载任务列表 */
  remoteTasks: StripRemoteTask[]
}>()

const emit = defineEmits<{
  (e: 'task-completed'): void
}>()

const expanded = ref(false)

const isRunningStatus = (status: string) => STORE_TASK_RUNNING_STATUSES.includes(status)

const runningRemoteTasks = computed(() =>
  props.remoteTasks.filter(t => isRunningStatus(t.status))
)
const activeTask = computed(() => runningRemoteTasks.value[0])

const remoteProgress = computed(() => {
  if (!runningRemoteTasks.value.length) return 100
  return Math.round(
    runningRemoteTasks.value.reduce((s, t) => s + (t.progress || 0), 0) /
    runningRemoteTasks.value.length
  )
})

const visible = computed(() =>
  props.builtin.active || props.remoteTasks.length > 0
)

/** 有任务终态（运行中 → 终态）时提示可展开；从运行中转为完成时通知父级刷新插件列表 */
watch(
  () => props.remoteTasks.map(t => `${t.taskId}:${t.status}`).join(','),
  (_cur, prev) => {
    if (!prev) return
    const prevMap = new Map(
      prev.split(',').filter(Boolean).map(s => {
        const [id, st] = s.split(':')
        return [id, st]
      })
    )
    let anyCompleted = false
    for (const t of props.remoteTasks) {
      const was = prevMap.get(t.taskId)
      if (was && isRunningStatus(was) &&
          (t.status === 'COMPLETED' || t.status === 'FAILED' || t.status === 'CANCELLED')) {
        anyCompleted = true
      }
    }
    if (anyCompleted) {
      expanded.value = true
      emit('task-completed')
    }
  }
)

async function onCancelTask(t: StripRemoteTask) {
  try {
    await ElMessageBox.confirm(
      `确认取消下载任务 "${t.pluginId}"？`,
      '确认取消',
      { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '保留' }
    )
    await pluginStoreApi.cancelTask(t.taskId)
    ElMessage.success('已取消')
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('取消失败：' + (e?.message || e))
  }
}

function progressStatus(status: string): '' | 'success' | 'exception' | 'warning' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'exception'
  if (status === 'CANCELLED') return 'warning'
  return ''
}

/** COMPLETED 即停止轮询的提交标记，历史轮询快照可能带过期进度——完成态恒显 100% */
function taskPercentage(t: StripRemoteTask): number {
  if (t.status === 'COMPLETED') return 100
  return t.progress || 0
}

function statusTagType(status: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED') return 'warning'
  return 'info'
}

function statusLabel(status: string): string {
  return ({
    PENDING: '等待中',
    RUNNING: '下载中',
    DOWNLOADING: '下载中',
    INSTALLING: '安装中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消'
  } as Record<string, string>)[status] || status
}

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(2) + ' ' + units[i]
}
</script>

<style lang="scss" scoped>
.task-strip {
  position: sticky;
  top: 0;
  z-index: 5;
  background: var(--platform-surface-2);
  border: 1px solid var(--platform-line);
  border-radius: 6px;
  padding: 8px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.strip-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.strip-info {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.strip-title {
  font-size: 13px;
  color: var(--platform-text-primary);
  white-space: nowrap;
}

.strip-progress {
  max-width: 360px;
  flex: 1;
}

.strip-bytes {
  font-size: 12px;
  color: var(--platform-text-secondary);
  white-space: nowrap;
}

.strip-icon.spinning {
  animation: strip-spin 1s linear infinite;
  color: var(--el-color-primary);
}

@keyframes strip-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.strip-detail {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-top: 1px dashed var(--platform-line);
  padding-top: 6px;
}

.detail-empty {
  font-size: 12px;
  color: var(--platform-text-secondary);
  padding: 4px 0;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;

  .t-name {
    min-width: 200px;
    color: var(--platform-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .t-progress {
    flex: 1;
  }
}

.flipped {
  transform: rotate(180deg);
}
</style>
