<template>
  <div class="remote-tab">
    <!-- 页头工具栏：搜索 + 分类 + 刷新 -->
    <div class="tab-toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索插件名称/描述"
        clearable
        style="width: 220px"
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select
        v-model="category"
        placeholder="全部分类"
        clearable
        style="width: 160px"
        @change="onFilterChange"
      >
        <el-option v-for="cat in categories" :key="cat" :label="cat" :value="cat" />
      </el-select>
      <el-radio-group v-model="installedFilter" size="small" @change="onFilterChange">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="uninstalled">未安装</el-radio-button>
        <el-radio-button value="installed">已安装</el-radio-button>
      </el-radio-group>
      <div class="toolbar-spacer" />
      <el-button @click="openConfigDialog">
        <el-icon><Setting /></el-icon>
        仓库设置
      </el-button>
      <el-button @click="loadList" :loading="loading">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <el-card shadow="never" class="page-card">
      <!-- 未配置仓库：引导去配置，不报错 -->
      <div v-if="!loading && unconfigured" class="empty-tip">
        <el-empty description="远端插件仓库未配置">
          <el-button type="primary" @click="openConfigDialog">去配置</el-button>
        </el-empty>
      </div>
      <div v-else-if="!filteredPlugins.length && !loading" class="empty-tip">
        <el-empty :description="loadError || '暂无插件'" />
      </div>
      <el-table
        :data="filteredPlugins"
        v-loading="loading"
        row-key="pluginId"
        :empty-text="loadError || '暂无插件'"
        @expand-change="onExpandChange"
      >
        <!-- 行内展开详情（ADR-0022 决策 6）：README + 文件列表 -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-detail" v-loading="detailLoadingId === row.pluginId">
              <template v-if="details[row.pluginId]">
                <div class="detail-meta">
                  <el-tag v-if="details[row.pluginId].category" type="info" size="small">
                    {{ details[row.pluginId].category }}
                  </el-tag>
                  <span>大小: {{ formatBytes(details[row.pluginId].size) }}</span>
                  <span>更新: {{ formatTime(details[row.pluginId].updatedAt) }}</span>
                </div>
                <el-divider content-position="left">README</el-divider>
                <div class="markdown-body" v-html="renderedReadme(row.pluginId)" />
                <el-divider content-position="left">文件列表</el-divider>
                <el-table :data="details[row.pluginId].fileList" stripe size="small" max-height="240">
                  <el-table-column prop="path" label="路径" min-width="240" />
                  <el-table-column label="大小" width="120">
                    <template #default="{ row: f }">{{ formatBytes(f.size) }}</template>
                  </el-table-column>
                </el-table>
                <div class="detail-actions">
                  <el-button
                    type="primary"
                    size="small"
                    :loading="downloadingId === row.pluginId"
                    @click="onDownload(row)"
                  >
                    下载到当前实例
                  </el-button>
                </div>
              </template>
              <div v-else class="detail-loading-tip">展开加载详情…</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="插件" min-width="260">
          <template #default="{ row }">
            <div class="plugin-name">
              <div class="name">
                {{ row.name }}
                <el-tag v-if="isInstalled(row)" type="success" size="small" class="installed-badge">
                  已安装
                </el-tag>
              </div>
              <div v-if="row.description" class="desc">{{ row.description }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.category" size="small" type="info">{{ row.category }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ row.size !== undefined ? formatBytes(row.size) : '-' }}</template>
        </el-table-column>

        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              size="small"
              link
              :loading="downloadingId === row.pluginId"
              @click="onDownload(row)"
            >
              下载
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-row">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="onPageChange"
          @size-change="onPageSizeChange"
        />
      </div>
    </el-card>

    <!-- 远端仓库设置 -->
    <el-dialog
      v-model="configDialogVisible"
      title="远端仓库设置"
      width="560px"
      @open="loadConfig"
    >
      <el-form :model="configForm" label-width="96px" v-loading="configLoading">
        <el-form-item label="仓库地址" required>
          <el-input
            v-model="configForm.repo"
            placeholder="owner/repo 或 https://github.com/owner/repo"
            clearable
          />
          <div class="form-tip">支持 GitHub 仓库链接或 owner/repo 形式，保存时自动归一</div>
        </el-form-item>
        <el-form-item label="分支">
          <el-input v-model="configForm.branch" placeholder="master" clearable />
        </el-form-item>
        <el-form-item label="加速代理">
          <el-input v-model="configForm.proxyUrl" placeholder="如 https://gh-proxy.com/，留空直连" clearable />
          <div class="form-tip">服务器无法直连 GitHub 时可配置代理前缀</div>
        </el-form-item>
        <el-form-item label="访问令牌">
          <el-input
            v-model="configForm.githubToken"
            type="password"
            show-password
            :placeholder="tokenPlaceholder"
          />
          <div class="form-tip">可选，提升 GitHub API 限额；留空保留已配置的令牌</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="onTestConfig" :loading="testing">测试连接</el-button>
        <el-button @click="configDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { pluginStoreApi, STORE_NOT_CONFIGURED_CODE, STORE_TASK_RUNNING_STATUSES } from '@/api'
import type { PageResult, StoreConfig } from '@/api'
import { ApiError } from '@/api/request'
import { usePluginStore } from '@/stores/plugin'
import { isPluginInstalled } from '@/utils/pluginMatch'
import { usePluginPagination } from '@/composables/usePluginPagination'

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

const props = defineProps<{
  /** 已安装插件名列表（来自插件管理列表，用于名称匹配角标） */
  installedNames: Array<string>
}>()

const emit = defineEmits<{
  (e: 'tasks-update', tasks: RemoteTask[]): void
  (e: 'plugins-changed'): void
}>()

interface RemoteTask {
  taskId: string
  pluginId: string
  status: string
  progress: number
  totalBytes: number
  downloadedBytes: number
  filename?: string
}

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const loading = ref(false)
const {
  page,
  pageSize,
  total,
  records: plugins,
  apply: applyPage,
  resetForFilter,
  changePage,
  changeSize
} = usePluginPagination<StorePlugin>()
const loadError = ref('')
let listRequestId = 0
const keyword = ref('')
const category = ref('')
const categories = ref<string[]>([])
const installedFilter = ref<'all' | 'uninstalled' | 'installed'>('all')

const details = ref<Record<string, StoreDetail>>({})
const detailLoadingId = ref('')
const downloadingId = ref('')

/** 仓库未配置状态：展示引导空态而非报错 */
const unconfigured = ref(false)

/** 仓库设置弹窗 */
const configDialogVisible = ref(false)
const configLoading = ref(false)
const testing = ref(false)
const saving = ref(false)
const tokenMasked = ref<string | null>(null)
const configForm = ref({ repo: '', branch: '', proxyUrl: '', githubToken: '' })

const tokenPlaceholder = computed(() =>
  tokenMasked.value ? `已配置（${tokenMasked.value}），留空保留` : '可选，提升 GitHub API 限额'
)

let pollTimer: ReturnType<typeof setInterval> | null = null

function isInstalled(row: StorePlugin): boolean {
  return isPluginInstalled(row.name, props.installedNames)
}

const filteredPlugins = computed(() => plugins.value)

/** 展开行时按需加载详情（已加载过的直接复用缓存） */
async function onExpandChange(row: StorePlugin, expandedRows: StorePlugin[]) {
  const expanded = expandedRows.some(r => r.pluginId === row.pluginId)
  if (!expanded) return
  if (details.value[row.pluginId]) return
  detailLoadingId.value = row.pluginId
  try {
    const d = await pluginStoreApi.detail(row.pluginId)
    details.value[row.pluginId] = d as StoreDetail
  } catch (e: any) {
    ElMessage.error('加载详情失败：' + (e?.message || e))
  } finally {
    detailLoadingId.value = ''
  }
}

function renderedReadme(pluginId: string): string {
  const raw = details.value[pluginId]?.readme || ''
  if (!raw) return '<p style="color: var(--platform-text-secondary)">暂无 README</p>'
  try {
    const html = marked.parse(raw, { async: false }) as string
    return DOMPurify.sanitize(html)
  } catch {
    return DOMPurify.sanitize(raw)
  }
}

onMounted(() => {
  loadList()
  loadCategories()
  refreshTasks()
})

async function loadCategories() {
  try {
    const data = await pluginStoreApi.categories()
    categories.value = Array.isArray(data) ? data : []
  } catch {
    // 分类仅用于筛选选项，失败时保留已有选项，不影响列表主流程
  }
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  const requestId = ++listRequestId
  try {
    const requestedPage = page.value
    const params: {
      keyword?: string
      category?: string
      installed?: 'all' | 'installed' | 'uninstalled'
      instanceId?: number
      page: number
      size: number
    } = {
      installed: installedFilter.value,
      instanceId: instanceId.value,
      page: requestedPage,
      size: pageSize.value
    }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (category.value) params.category = category.value
    const data = await pluginStoreApi.list(params)
    if (requestId !== listRequestId) return
    const effectivePage = applyPage(data as PageResult<StorePlugin>)
    if (effectivePage !== requestedPage) {
      return loadList()
    }
    unconfigured.value = false
    if (categories.value.length === 0) {
      categories.value = Array.from(new Set(
        plugins.value.map(p => p.category).filter((c): c is string => Boolean(c))
      ))
    }
  } catch (e: any) {
    if (requestId !== listRequestId) return
    if (e instanceof ApiError && e.code === STORE_NOT_CONFIGURED_CODE) {
      // 未配置仓库：展示引导空态，不弹错误
      plugins.value = []
      total.value = 0
      unconfigured.value = true
    } else {
      loadError.value = e?.message || String(e)
      ElMessage.error('加载远端插件仓库失败：' + loadError.value)
      plugins.value = []
      total.value = 0
    }
  } finally {
    if (requestId === listRequestId) {
      loading.value = false
    }
  }
}

function onFilterChange() {
  resetForFilter()
  loadList()
}

function onPageChange(nextPage: number) {
  changePage(nextPage)
  loadList()
}

function onPageSizeChange(nextSize: number) {
  changeSize(nextSize)
  loadList()
}

// ========== 仓库设置 ==========

function openConfigDialog() {
  configDialogVisible.value = true
}

async function loadConfig() {
  configLoading.value = true
  try {
    const cfg = (await pluginStoreApi.getConfig()) as StoreConfig
    configForm.value.repo = cfg.repo || ''
    configForm.value.branch = cfg.branch || ''
    configForm.value.proxyUrl = cfg.proxyUrl || ''
    configForm.value.githubToken = ''
    tokenMasked.value = cfg.githubTokenMasked || null
  } catch (e: any) {
    ElMessage.error('加载仓库配置失败：' + (e?.message || e))
  } finally {
    configLoading.value = false
  }
}

function configPayload() {
  return {
    repo: configForm.value.repo.trim(),
    branch: configForm.value.branch.trim() || undefined,
    proxyUrl: configForm.value.proxyUrl.trim() || undefined,
    githubToken: configForm.value.githubToken.trim() || undefined
  }
}

async function onTestConfig() {
  if (!configForm.value.repo.trim()) {
    ElMessage.warning('请先填写仓库地址')
    return
  }
  testing.value = true
  try {
    const res = await pluginStoreApi.testConfig(configPayload())
    ElMessage.success(`连接成功，发现 ${res.pluginCount} 个插件（${res.repo}@${res.branch}）`)
  } catch (e: any) {
    ElMessage.error('连接失败：' + (e?.message || e))
  } finally {
    testing.value = false
  }
}

async function onSaveConfig() {
  if (!configForm.value.repo.trim()) {
    ElMessage.warning('请先填写仓库地址')
    return
  }
  saving.value = true
  try {
    await pluginStoreApi.saveConfig(configPayload())
    ElMessage.success('仓库配置已保存')
    configDialogVisible.value = false
    loadList()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    saving.value = false
  }
}

async function onDownload(plugin: StorePlugin) {
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
  try {
    const data = await pluginStoreApi.tasks(instanceId.value)
    const tasks = (Array.isArray(data) ? data : []) as RemoteTask[]
    emit('tasks-update', tasks)
    const hasRunning = tasks.some(t => STORE_TASK_RUNNING_STATUSES.includes(t.status))
    if (hasRunning) {
      startPolling()
    } else {
      stopPolling()
    }
  } catch {
    // 任务列表加载失败不打扰用户（strip 静默降级）
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(refreshTasks, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
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

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style lang="scss" scoped>
.remote-tab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tab-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.toolbar-spacer {
  flex: 1;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.empty-tip {
  padding: 32px 0;
}

.form-tip {
  font-size: 12px;
  color: var(--platform-text-secondary);
  line-height: 1.5;
  margin-top: 4px;
  width: 100%;
}

.plugin-name {
  .name {
    font-weight: 500;
    color: var(--platform-text-primary);
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .desc {
    font-size: 12px;
    color: var(--platform-text-secondary);
    margin-top: 2px;
  }
}

.expand-detail {
  padding: 8px 16px 16px;
}

.detail-loading-tip {
  font-size: 13px;
  color: var(--platform-text-secondary);
  padding: 12px 0;
}

.detail-meta {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 13px;
  color: var(--platform-text-secondary);
  flex-wrap: wrap;
}

.markdown-body {
  word-break: break-word;
  line-height: 1.6;

  :deep(pre) {
    background: var(--platform-surface-2);
    padding: 12px;
    border-radius: 4px;
    overflow-x: auto;
  }

  :deep(code) {
    background: var(--platform-surface-2);
    padding: 2px 4px;
    border-radius: 3px;
  }

  :deep(pre code) {
    background: transparent;
    padding: 0;
  }
}

.detail-actions {
  margin-top: 12px;
  display: flex;
}
</style>
