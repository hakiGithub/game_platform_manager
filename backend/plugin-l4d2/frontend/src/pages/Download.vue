<template>
  <div class="download-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / DOWNLOADS</span>
        <h2>资源下载</h2>
        <p>URL 与 Steam Workshop 资源下载任务管理</p>
      </div>
      <div class="header-actions">
        <el-button size="small" @click="loadTasks" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- 顶部：URL / Workshop Tab 切换 -->
    <el-card shadow="never" class="page-card form-card">
      <el-tabs v-model="activeTab">
        <!-- Tab 1: URL 下载表单 -->
        <el-tab-pane label="URL 下载" name="url">
          <el-form :model="urlForm" label-width="100px" class="url-form">
            <el-form-item label="下载 URL" required>
              <el-input
                v-model="urlForm.url"
                type="textarea"
                :rows="4"
                placeholder="支持多个 URL，每行一个；或将 http(s) 链接粘贴在同一行也可自动识别"
              />
            </el-form-item>
            <el-form-item label="文件名">
              <el-input
                v-model="urlForm.filename"
                placeholder="可选；留空时由后端从 URL 推断"
              />
            </el-form-item>
            <el-form-item label="Referer">
              <el-input
                v-model="urlForm.referer"
                placeholder="可选；部分资源站点需要"
              />
            </el-form-item>
            <el-form-item label="目标路径">
              <el-input
                v-model="urlForm.targetPath"
                placeholder="可选；默认 addons/"
              />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="urlSubmitting"
                @click="submitUrl"
              >
                开始下载
              </el-button>
              <el-button @click="resetUrlForm">清空表单</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- Tab 2: Workshop 下载表单 -->
        <el-tab-pane label="Workshop 下载" name="workshop">
          <el-form label-width="120px" class="workshop-form">
            <el-form-item label="Workshop 链接" required>
              <el-input
                v-model="workshopInput"
                placeholder="输入 Workshop URL 或纯数字 ID，例如 https://steamcommunity.com/sharedfiles/filedetails/?id=123456 或 123456"
                clearable
                @keyup.enter="parseWorkshop"
              />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="workshopParsing"
                @click="parseWorkshop"
              >
                解析预览
              </el-button>
              <el-button
                type="success"
                :loading="workshopDownloading"
                :disabled="!workshopInput.trim()"
                @click="downloadAllWorkshop"
              >
                直接下载全部
              </el-button>
              <span class="form-tip">
                提示：Workshop 解析仅作预览，下载会创建全部条目的任务
              </span>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 任务列表 -->
    <el-card shadow="never" class="page-card tasks-card">
      <template #header>
        <div class="card-header">
          <span>下载任务</span>
          <div class="header-actions">
            <el-button
              size="small"
              type="warning"
              :loading="clearing"
              :disabled="!hasFinishedTasks"
              @click="clearFinished"
            >
              清理已完成
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        :data="tasks"
        v-loading="loading"
        stripe
        empty-text="暂无下载任务"
      >
        <el-table-column label="任务 ID" width="120">
          <template #default="{ row }">
            <span :title="row.taskId">{{ shortId(row.taskId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="row.taskType === 'WORKSHOP' ? 'warning' : 'info'" size="small">
              {{ row.taskType === 'WORKSHOP' ? 'Workshop' : 'URL' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文件名" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.filename || row.workshopTitle || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="220">
          <template #default="{ row }">
            <el-progress
              :percentage="row.progress || 0"
              :status="progressStatus(row.status)"
              :stroke-width="14"
              :text-inside="true"
            />
            <div class="progress-bytes">
              <span>{{ row.formattedSize || '-' }}</span>
              <span v-if="row.status === 'DOWNLOADING'" class="speed-text">
                {{ row.formattedSpeed || '-' }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.startTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              link
              @click="showDetail(row)"
            >
              详情
            </el-button>
            <el-button
              v-if="canCancel(row.status)"
              size="small"
              type="danger"
              link
              :loading="cancelingId === row.taskId"
              @click="cancelTask(row.taskId)"
            >
              取消
            </el-button>
            <el-button
              v-if="canDelete(row.status)"
              size="small"
              type="danger"
              link
              :loading="deletingId === row.taskId"
              @click="deleteTask(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Workshop 解析结果弹窗 -->
    <el-dialog
      v-model="workshopDialogVisible"
      title="Workshop 解析结果"
      width="80%"
      destroy-on-close
    >
      <div v-if="workshopParseResult" class="workshop-preview">
        <div class="preview-meta">
          <el-tag type="info">来源 ID: {{ workshopParseResult.sourceId }}</el-tag>
          <el-tag type="success">{{ workshopItems.length }} 个条目</el-tag>
          <el-tag type="warning">{{ workshopItems.filter(i => i.hasFileUrl).length }} 个可下载</el-tag>
        </div>
        <el-table
          :data="workshopItems"
          stripe
          max-height="420"
          empty-text="解析结果为空"
        >
          <el-table-column label="预览" width="100">
            <template #default="{ row }">
              <el-image
                v-if="row.previewUrl"
                :src="row.previewUrl"
                fit="cover"
                style="width: 80px; height: 45px; border-radius: 4px;"
                :preview-src-list="[row.previewUrl]"
                preview-teleported
              />
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
          <el-table-column prop="filename" label="文件名" min-width="160" show-overflow-tooltip />
          <el-table-column prop="fileSize" label="大小" width="120">
            <template #default="{ row }">{{ row.fileSize || '-' }}</template>
          </el-table-column>
          <el-table-column label="可下载" width="100">
            <template #default="{ row }">
              <el-tag :type="row.hasFileUrl ? 'success' : 'info'" size="small">
                {{ row.hasFileUrl ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="ID" width="140" prop="publishedFileId" />
        </el-table>
      </div>
      <template #footer>
        <el-button @click="workshopDialogVisible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="workshopDownloading"
          :disabled="!workshopItems.length"
          @click="downloadAllWorkshop"
        >
          下载全部
        </el-button>
      </template>
    </el-dialog>

    <!-- 任务详情弹窗 -->
    <el-dialog
      v-model="detailDialogVisible"
      title="任务详情"
      width="640px"
      destroy-on-close
    >
      <el-descriptions v-if="detailTask" :column="1" border>
        <el-descriptions-item label="任务 ID">{{ detailTask.taskId }}</el-descriptions-item>
        <el-descriptions-item label="实例 ID">{{ detailTask.instanceId }}</el-descriptions-item>
        <el-descriptions-item label="任务类型">
          {{ detailTask.taskType === 'WORKSHOP' ? 'Workshop' : 'URL' }}
        </el-descriptions-item>
        <el-descriptions-item label="文件名">{{ detailTask.filename || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(detailTask.status)" size="small">
            {{ statusLabel(detailTask.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="进度">
          {{ detailTask.progress || 0 }}%
        </el-descriptions-item>
        <el-descriptions-item label="文件大小">
          {{ detailTask.formattedSize || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="下载速度">
          {{ detailTask.formattedSpeed || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="目标路径">
          {{ detailTask.targetPath || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="任务 URL">
          <span class="break-all">{{ detailTask.taskUrl || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="detailTask.workshopId" label="Workshop ID">
          {{ detailTask.workshopId }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detailTask.workshopTitle" label="Workshop 标题">
          {{ detailTask.workshopTitle }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detailTask.previewUrl" label="预览图">
          <el-image
            :src="detailTask.previewUrl"
            fit="cover"
            style="width: 160px; height: 90px; border-radius: 4px;"
            :preview-src-list="[detailTask.previewUrl]"
            preview-teleported
          />
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">
          {{ formatTime(detailTask.startTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="完成时间">
          {{ formatTime(detailTask.completeTime) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detailTask.errorMessage" label="错误信息">
          <span class="error-text">{{ detailTask.errorMessage }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  downloadApi,
  type DownloadTaskVO,
  type WorkshopItemVO,
  type WorkshopParseResultVO
} from '@/api'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

// Tab 切换
const activeTab = ref<'url' | 'workshop'>('url')

// ===== URL 下载表单 =====
const urlForm = ref({
  url: '',
  filename: '',
  referer: '',
  targetPath: ''
})
const urlSubmitting = ref(false)

// ===== Workshop 下载表单 =====
const workshopInput = ref('')
const workshopParsing = ref(false)
const workshopDownloading = ref(false)
const workshopDialogVisible = ref(false)
const workshopParseResult = ref<WorkshopParseResultVO | null>(null)

const workshopItems = computed<WorkshopItemVO[]>(() => {
  return workshopParseResult.value?.items || []
})

// ===== 任务列表 =====
const tasks = ref<DownloadTaskVO[]>([])
const loading = ref(false)
const clearing = ref(false)
const cancelingId = ref('')
const deletingId = ref('')

// 任务详情
const detailDialogVisible = ref(false)
const detailTask = ref<DownloadTaskVO | null>(null)

// 轮询定时器
let pollTimer: ReturnType<typeof setInterval> | null = null

const hasFinishedTasks = computed(() => {
  return tasks.value.some(
    t => t.status === 'COMPLETED' || t.status === 'CANCELLED'
  )
})

// ===== URL 表单提交 =====
async function submitUrl() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  const rawUrl = urlForm.value.url.trim()
  if (!rawUrl) {
    ElMessage.warning('请填写下载 URL')
    return
  }
  urlSubmitting.value = true
  try {
    const payload: {
      instanceId: number
      url: string
      filename?: string
      referer?: string
      targetPath?: string
    } = {
      instanceId: instanceId.value,
      url: rawUrl
    }
    if (urlForm.value.filename.trim()) payload.filename = urlForm.value.filename.trim()
    if (urlForm.value.referer.trim()) payload.referer = urlForm.value.referer.trim()
    if (urlForm.value.targetPath.trim()) payload.targetPath = urlForm.value.targetPath.trim()

    const taskIds = await downloadApi.createUrlTask(payload)
    ElMessage.success(`已创建 ${taskIds?.length || 0} 个下载任务`)
    resetUrlForm()
    await loadTasks()
    startPolling()
  } catch (e: any) {
    ElMessage.error('创建下载任务失败：' + (e?.message || e))
  } finally {
    urlSubmitting.value = false
  }
}

function resetUrlForm() {
  urlForm.value = {
    url: '',
    filename: '',
    referer: '',
    targetPath: ''
  }
}

// ===== Workshop 解析与下载 =====
async function parseWorkshop() {
  const input = workshopInput.value.trim()
  if (!input) {
    ElMessage.warning('请输入 Workshop URL 或 ID')
    return
  }
  workshopParsing.value = true
  try {
    const result = await downloadApi.parseWorkshop(input)
    workshopParseResult.value = result
    workshopDialogVisible.value = true
    if (!result.items || result.items.length === 0) {
      ElMessage.info('解析结果为空')
    } else {
      ElMessage.success(`解析到 ${result.items.length} 个条目`)
    }
  } catch (e: any) {
    ElMessage.error('解析 Workshop 失败：' + (e?.message || e))
  } finally {
    workshopParsing.value = false
  }
}

async function downloadAllWorkshop() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  const input = workshopInput.value.trim()
  if (!input) {
    ElMessage.warning('请输入 Workshop URL 或 ID')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认下载 Workshop 全部条目？将一次性创建所有可下载项的任务。',
      '确认下载',
      { type: 'info', confirmButtonText: '下载', cancelButtonText: '取消' }
    )
    workshopDownloading.value = true
    const taskIds = await downloadApi.createWorkshopTask({
      instanceId: instanceId.value,
      workshopUrlOrId: input
    })
    ElMessage.success(`已创建 ${taskIds?.length || 0} 个下载任务`)
    workshopDialogVisible.value = false
    await loadTasks()
    startPolling()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('创建 Workshop 下载任务失败：' + (e?.message || e))
    }
  } finally {
    workshopDownloading.value = false
  }
}

// ===== 任务列表 =====
async function loadTasks() {
  if (!instanceId.value) {
    tasks.value = []
    return
  }
  loading.value = true
  try {
    const data = await downloadApi.listTasks(instanceId.value)
    tasks.value = Array.isArray(data) ? data : []
    // 全部终态时停止轮询
    const hasActive = tasks.value.some(
      t => t.status === 'PENDING' || t.status === 'DOWNLOADING'
    )
    if (!hasActive) {
      stopPolling()
    } else {
      startPolling()
    }
  } catch (e: any) {
    ElMessage.error('加载任务列表失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function cancelTask(taskId: string) {
  try {
    await ElMessageBox.confirm(
      '确认取消该下载任务？已下载的部分将被清理。',
      '确认取消',
      { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '保留' }
    )
    cancelingId.value = taskId
    await downloadApi.cancelTask(taskId)
    ElMessage.success('已请求取消')
    await loadTasks()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('取消任务失败：' + (e?.message || e))
    }
  } finally {
    cancelingId.value = ''
  }
}

async function deleteTask(task: DownloadTaskVO) {
  try {
    await ElMessageBox.confirm(
      `确认删除任务记录 "${task.filename || task.taskId}"？此操作不可恢复。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    deletingId.value = task.taskId
    await downloadApi.deleteTask(task.taskId)
    ElMessage.success('已删除')
    await loadTasks()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除任务失败：' + (e?.message || e))
    }
  } finally {
    deletingId.value = ''
  }
}

async function clearFinished() {
  const finished = tasks.value.filter(
    t => t.status === 'COMPLETED' || t.status === 'CANCELLED'
  )
  if (!finished.length) {
    ElMessage.info('没有可清理的已完成任务')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认清理 ${finished.length} 个已完成/已取消的任务记录？FAILED 任务将保留以便排查。`,
      '确认清理',
      { type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消' }
    )
    clearing.value = true
    let success = 0
    let failed = 0
    for (const task of finished) {
      try {
        await downloadApi.deleteTask(task.taskId)
        success++
      } catch {
        failed++
      }
    }
    ElMessage.success(`已清理 ${success} 个任务${failed ? `，${failed} 个失败` : ''}`)
    await loadTasks()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('清理失败：' + (e?.message || e))
    }
  } finally {
    clearing.value = false
  }
}

function showDetail(task: DownloadTaskVO) {
  detailTask.value = task
  detailDialogVisible.value = true
}

// ===== 轮询控制 =====
function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(loadTasks, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ===== 辅助函数 =====
function shortId(taskId: string): string {
  if (!taskId) return '-'
  return taskId.length > 8 ? taskId.slice(0, 8) : taskId
}

function canCancel(status: string): boolean {
  return status === 'PENDING' || status === 'DOWNLOADING'
}

function canDelete(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED' || status === 'PENDING_MANUAL'
}

function progressStatus(status: string): '' | 'success' | 'exception' | 'warning' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'exception'
  if (status === 'CANCELLED') return 'warning'
  if (status === 'PENDING_MANUAL') return 'warning'
  return ''
}

function statusTagType(status: string): 'success' | 'info' | 'warning' | 'danger' | 'primary' {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'DOWNLOADING':
      return 'primary'
    case 'PENDING':
      return 'info'
    case 'FAILED':
      return 'danger'
    case 'CANCELLED':
      return 'warning'
    case 'PENDING_MANUAL':
      return 'warning'
    default:
      return 'info'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '等待中'
    case 'DOWNLOADING':
      return '下载中'
    case 'COMPLETED':
      return '已完成'
    case 'FAILED':
      return '失败'
    case 'CANCELLED':
      return '已取消'
    case 'PENDING_MANUAL':
      return '待手动'
    default:
      return status
  }
}

function formatTime(t?: string): string {
  if (!t) return '-'
  try {
    return new Date(t).toLocaleString('zh-CN')
  } catch {
    return t
  }
}

// ===== 生命周期 =====
onMounted(() => {
  loadTasks()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.download-page {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-card,
.tasks-card {
  background: var(--platform-surface-1);
  border: 1px solid var(--platform-line);
  border-radius: 6px;
  box-shadow: none;
}

.url-form,
.workshop-form {
  max-width: 720px;
  margin-top: 8px;
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--platform-text-secondary);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.progress-bytes {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 12px;
  color: var(--platform-text-secondary);
}

.speed-text {
  color: var(--platform-cyan);
}

.workshop-preview {
  padding: 0 4px;
}

.preview-meta {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.break-all {
  word-break: break-all;
}

.error-text {
  color: var(--platform-red);
  word-break: break-all;
}
</style>
