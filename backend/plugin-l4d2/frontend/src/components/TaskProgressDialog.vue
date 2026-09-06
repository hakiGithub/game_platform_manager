<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    width="460"
    align-center
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
  >
    <div class="task-progress">
      <el-progress
        :percentage="displayPercent"
        :status="progressStatus"
        :stroke-width="20"
        :text-inside="true"
      />
      <div class="progress-message">{{ displayMessage }}</div>
      <div v-if="errorMessage" class="progress-error">{{ errorMessage }}</div>
    </div>
    <template #footer>
      <el-button v-if="cancellable && phase === 'external'" @click="emit('cancel')">
        取消
      </el-button>
      <!-- 终态由父组件负责关闭弹窗；非终态期间不提供关闭按钮（等待直到处理完成） -->
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'

/**
 * 通用任务进度弹窗。
 *
 * 两阶段复用（地图上传）：phase='external' 展示 HTTP 上传段进度；
 * taskId 就绪后切到 phase='task'，内部按 pollIntervalMs 轮询 pollFn(taskId)，
 * 把任务 progress 线性映射到 taskRange 区间，直到终态（COMPLETED/FAILED/CANCELLED）
 * 发出 completed/failed 事件并停止轮询——弹窗关闭由父组件通过 visible 控制。
 * 插件安装等纯任务场景全程 phase='task'，无 external 段。
 */
export interface TaskPollStatus {
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  progress?: number
  progressMessage?: string
  errorMessage?: string
  resultSummary?: string
  result?: any
}

const props = withDefaults(defineProps<{
  visible: boolean
  title: string
  /** 任务阶段轮询函数 */
  pollFn: (taskId: string) => Promise<TaskPollStatus>
  taskId?: string
  /** external：HTTP 上传段（外部驱动进度）；task：轮询任务进度 */
  phase: 'external' | 'task'
  /** external 段进度（0-100）与文案 */
  externalPercent?: number
  externalMessage?: string
  /** 任务 progress 到弹窗百分比的映射区间 */
  taskRange?: [number, number]
  /** external 段是否提供取消按钮 */
  cancellable?: boolean
  pollIntervalMs?: number
}>(), {
  taskId: '',
  externalPercent: 0,
  externalMessage: '',
  taskRange: () => [0, 100],
  cancellable: false,
  pollIntervalMs: 2000,
})

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  /** 每次轮询到状态都会发出（父组件可用于刷新自己的展示） */
  (e: 'status', st: TaskPollStatus): void
  (e: 'completed', st: TaskPollStatus): void
  (e: 'failed', st: TaskPollStatus): void
  (e: 'cancel'): void
}>()

const pollTimer = ref<ReturnType<typeof setInterval> | null>(null)
const lastStatus = ref<TaskPollStatus | null>(null)
const errorMessage = ref('')

const displayPercent = computed(() => {
  if (props.phase === 'external') {
    return clampPercent(props.externalPercent)
  }
  const [start, end] = props.taskRange
  const p = lastStatus.value?.progress
  if (typeof p !== 'number') return clampPercent(start)
  return clampPercent(start + (end - start) * p / 100)
})

const displayMessage = computed(() => {
  if (props.phase === 'external') {
    return props.externalMessage || '上传中...'
  }
  return lastStatus.value?.progressMessage
    || lastStatus.value?.resultSummary
    || '服务器处理中...'
})

const progressStatus = computed(() => {
  const st = lastStatus.value?.status
  if (st === 'COMPLETED') return 'success'
  if (st === 'FAILED' || st === 'CANCELLED' || errorMessage.value) return 'exception'
  return ''
})

function clampPercent(p: number): number {
  return Math.max(0, Math.min(100, Math.round(p)))
}

function stopPolling() {
  if (pollTimer.value) {
    clearInterval(pollTimer.value)
    pollTimer.value = null
  }
}

function startPolling(taskId: string) {
  stopPolling()
  pollTimer.value = setInterval(async () => {
    try {
      const st = await props.pollFn(taskId)
      lastStatus.value = st
      errorMessage.value = st.errorMessage || ''
      emit('status', st)
      if (st.status === 'COMPLETED') {
        stopPolling()
        emit('completed', st)
      } else if (st.status === 'FAILED' || st.status === 'CANCELLED') {
        stopPolling()
        emit('failed', st)
      }
      // PENDING / RUNNING 继续轮询
    } catch {
      // 单次轮询失败忽略（网络抖动），等待下一次
    }
  }, props.pollIntervalMs)
}

watch(
  () => [props.visible, props.phase, props.taskId] as const,
  ([visible, phase, taskId]) => {
    if (visible && phase === 'task' && taskId) {
      startPolling(taskId)
    } else {
      stopPolling()
    }
    if (!visible) {
      // 关闭时复位轮询状态，下次打开从头开始
      lastStatus.value = null
      errorMessage.value = ''
    }
  },
  { immediate: true }
)

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.task-progress {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.progress-message {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: center;
  word-break: break-all;
}
.progress-error {
  font-size: 13px;
  color: var(--el-color-danger);
  text-align: center;
  word-break: break-all;
}
</style>
