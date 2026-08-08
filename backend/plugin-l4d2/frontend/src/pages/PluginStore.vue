<template>
  <div class="plugin-store-page">
    <el-card v-loading="loading">
      <template #header>
        <div class="card-header">
          <span>插件商店</span>
          <div class="header-actions">
            <el-input
              v-model="keyword"
              placeholder="搜索插件名称/描述"
              clearable
              style="width: 220px"
              @keyup.enter="loadList"
              @clear="loadList"
            />
            <el-select
              v-model="category"
              placeholder="全部分类"
              clearable
              teleported
              style="width: 160px"
              @change="loadList"
            >
              <el-option
                v-for="cat in categories"
                :key="cat"
                :label="cat"
                :value="cat"
              />
            </el-select>
            <el-button type="primary" @click="loadList" :loading="loading">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <div v-if="!plugins.length && !loading" class="empty-tip">
        <el-empty description="暂无插件" />
      </div>

      <el-row :gutter="16">
        <el-col
          v-for="plugin in plugins"
          :key="plugin.pluginId"
          :xs="24"
          :sm="12"
          :md="8"
          :lg="6"
        >
          <el-card
            class="plugin-card"
            shadow="hover"
            @click="openDetail(plugin)"
          >
            <div class="plugin-card-name">{{ plugin.name }}</div>
            <div class="plugin-card-desc">{{ plugin.description || '无描述' }}</div>
            <div class="plugin-card-meta">
              <el-tag v-if="plugin.category" size="small" type="info">{{ plugin.category }}</el-tag>
              <span v-if="plugin.size !== undefined" class="meta-item">
                {{ formatBytes(plugin.size) }}
              </span>
              <span v-if="plugin.updatedAt" class="meta-item">{{ formatTime(plugin.updatedAt) }}</span>
            </div>
            <div class="plugin-card-actions">
              <el-button
                type="primary"
                size="small"
                :loading="downloadingId === plugin.pluginId"
                @click.stop="onDownload(plugin)"
              >
                下载到当前实例
              </el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-card class="tasks-card">
      <template #header>
        <div class="card-header">
          <span>下载任务</span>
          <el-button size="small" @click="refreshTasks" :loading="tasksLoading">刷新</el-button>
        </div>
      </template>
      <el-table :data="tasks" v-loading="tasksLoading" stripe>
        <el-table-column prop="pluginId" label="插件ID" min-width="160" />
        <el-table-column prop="filename" label="文件名" min-width="160">
          <template #default="{ row }">{{ row.filename || '-' }}</template>
        </el-table-column>
        <el-table-column label="进度" min-width="240">
          <template #default="{ row }">
            <el-progress
              :percentage="row.progress || 0"
              :status="progressStatus(row.status)"
            />
            <div class="progress-bytes">
              {{ formatBytes(row.downloadedBytes || 0) }} / {{ formatBytes(row.totalBytes || 0) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'RUNNING' || row.status === 'PENDING'"
              type="danger"
              size="small"
              link
              @click="onCancelTask(row)"
            >
              取消
            </el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer
      v-model="showDetail"
      :title="detail?.name || '插件详情'"
      size="50%"
      direction="rtl"
    >
      <div v-loading="detailLoading">
        <div v-if="detail" class="detail-content">
          <div class="detail-meta">
            <el-tag v-if="detail.category" type="info">{{ detail.category }}</el-tag>
            <span v-if="detail.size !== undefined">大小: {{ formatBytes(detail.size) }}</span>
            <span v-if="detail.updatedAt">更新: {{ formatTime(detail.updatedAt) }}</span>
          </div>
          <el-divider content-position="left">README</el-divider>
          <div class="markdown-body" v-html="renderedReadme" />
          <el-divider content-position="left">文件列表</el-divider>
          <el-table :data="detail.fileList" stripe size="small">
            <el-table-column prop="path" label="路径" min-width="240" />
            <el-table-column label="大小" width="120">
              <template #default="{ row }">{{ formatBytes(row.size) }}</template>
            </el-table-column>
          </el-table>
          <div class="detail-actions">
            <el-button
              type="primary"
              :loading="downloadingId === detail.pluginId"
              @click="onDownload(detail)"
            >
              下载到当前实例
            </el-button>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { pluginStoreApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'

interface StorePlugin {
  pluginId: string
  name: string
  description?: string
  category?: string
  size?: number
  updatedAt?: string
}

interface StoreDetail {
  pluginId: string
  name: string
  description?: string
  category?: string
  size?: number
  updatedAt?: string
  readme: string
  fileList: Array<{ path: string; size: number }>
}

interface DownloadTask {
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
}

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const loading = ref(false)
const tasksLoading = ref(false)
const keyword = ref('')
const category = ref('')
const categories = ref<string[]>([])

const plugins = ref<StorePlugin[]>([])
const tasks = ref<DownloadTask[]>([])

const showDetail = ref(false)
const detailLoading = ref(false)
const detail = ref<StoreDetail | null>(null)
const downloadingId = ref('')

let pollTimer: ReturnType<typeof setInterval> | null = null

const renderedReadme = computed(() => {
  const raw = detail.value?.readme || ''
  if (!raw) return '<p style="color: var(--el-text-color-secondary)">暂无 README</p>'
  try {
    const html = marked.parse(raw, { async: false }) as string
    return DOMPurify.sanitize(html)
  } catch {
    return DOMPurify.sanitize(raw)
  }
})

async function loadList() {
  loading.value = true
  try {
    const params: { keyword?: string; category?: string } = {}
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (category.value) params.category = category.value
    const data = await pluginStoreApi.list(params)
    plugins.value = Array.isArray(data) ? data : []
    const cats = new Set<string>()
    plugins.value.forEach(p => {
      if (p.category) cats.add(p.category)
    })
    categories.value = Array.from(cats)
  } catch (e: any) {
    ElMessage.error('加载插件商店失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function openDetail(plugin: StorePlugin) {
  showDetail.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await pluginStoreApi.detail(plugin.pluginId)
  } catch (e: any) {
    ElMessage.error('加载详情失败：' + (e?.message || e))
  } finally {
    detailLoading.value = false
  }
}

async function onDownload(plugin: StorePlugin | StoreDetail) {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认将插件 "${plugin.name}" 下载到当前实例？`,
      '确认下载',
      { type: 'info', confirmButtonText: '下载', cancelButtonText: '取消' }
    )
    downloadingId.value = plugin.pluginId
    await pluginStoreApi.download({
      instanceId: instanceId.value,
      pluginId: plugin.pluginId
    })
    ElMessage.success('下载任务已创建')
    refreshTasks()
    startPolling()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('下载失败：' + (e?.message || e))
    }
  } finally {
    downloadingId.value = ''
  }
}

async function refreshTasks() {
  if (!instanceId.value) return
  tasksLoading.value = true
  try {
    const data = await pluginStoreApi.tasks(instanceId.value)
    tasks.value = Array.isArray(data) ? data : []
    const hasRunning = tasks.value.some(
      t => t.status === 'RUNNING' || t.status === 'PENDING'
    )
    if (!hasRunning) {
      stopPolling()
    } else {
      startPolling()
    }
  } catch (e: any) {
    ElMessage.error('加载任务列表失败：' + (e?.message || e))
  } finally {
    tasksLoading.value = false
  }
}

async function onCancelTask(row: DownloadTask) {
  try {
    await ElMessageBox.confirm(
      `确认取消下载任务 "${row.pluginId}"？`,
      '确认取消',
      { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '保留' }
    )
    await pluginStoreApi.cancelTask(row.taskId)
    ElMessage.success('已取消')
    refreshTasks()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('取消失败：' + (e?.message || e))
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    refreshTasks()
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function progressStatus(status: string): '' | 'success' | 'exception' | 'warning' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'exception'
  if (status === 'CANCELLED') return 'warning'
  return ''
}

function statusTagType(status: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED') return 'warning'
  return 'info'
}

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(2) + ' ' + units[i]
}

function formatTime(t?: string): string {
  if (!t) return '-'
  try {
    return new Date(t).toLocaleString('zh-CN')
  } catch {
    return t
  }
}

onMounted(() => {
  loadList()
  refreshTasks()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.plugin-store-page {
  padding: 12px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.empty-tip {
  padding: 32px 0;
}
.plugin-card {
  margin-bottom: 16px;
  cursor: pointer;
  transition: transform 0.15s;
}
.plugin-card:hover {
  transform: translateY(-2px);
}
.plugin-card-name {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
}
.plugin-card-desc {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  min-height: 40px;
  margin-bottom: 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.plugin-card-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.meta-item {
  font-size: 12px;
}
.plugin-card-actions {
  display: flex;
  justify-content: flex-end;
}
.tasks-card {
  margin-top: 16px;
}
.progress-bytes {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.detail-content {
  padding: 0 8px;
}
.detail-meta {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  flex-wrap: wrap;
}
.markdown-body {
  word-break: break-word;
  line-height: 1.6;
}
.markdown-body :deep(pre) {
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 4px;
  overflow-x: auto;
}
.markdown-body :deep(code) {
  background: var(--el-fill-color-light);
  padding: 2px 4px;
  border-radius: 3px;
}
.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
}
.detail-actions {
  margin-top: 20px;
  display: flex;
  justify-content: center;
}
</style>
