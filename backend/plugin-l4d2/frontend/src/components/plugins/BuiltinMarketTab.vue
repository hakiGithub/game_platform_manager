<template>
  <div class="builtin-tab">
    <!-- 页头工具栏：搜索 + 分类筛选 + 安装状态筛选 + 批量操作 -->
    <div class="tab-toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索插件名/描述"
        clearable
        style="width: 220px"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-radio-group v-model="categoryFilter" size="small">
        <el-radio-button
          v-for="opt in categoryOptions"
          :key="opt.value"
          :value="opt.value"
        >
          {{ opt.label }}（{{ opt.count }}）
        </el-radio-button>
      </el-radio-group>

      <el-radio-group v-model="installedFilter" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="uninstalled">未安装</el-radio-button>
        <el-radio-button value="installed">已安装</el-radio-button>
      </el-radio-group>

      <div class="toolbar-spacer" />

      <el-button-group size="small">
        <el-button @click="onSelectAllUninstalled">全选未安装</el-button>
        <el-button @click="onClearSelection">清空选择</el-button>
      </el-button-group>
      <span class="builtin-summary">
        已选 <b>{{ selectedIds.length }}</b> 个 · 合计 <b>{{ formatSize(selectedSize) }}</b>
      </span>
      <el-button
        type="primary"
        :loading="installing"
        :disabled="!selectedIds.length || installing"
        @click="onBatchInstall"
      >
        安装选中 {{ selectedIds.length }} 个
      </el-button>
    </div>

    <el-card shadow="never" class="page-card">
      <el-table
        :data="filteredRows"
        v-loading="loading"
        size="default"
        :row-key="rowKey"
        @selection-change="onSelectionChange"
        :row-class-name="rowClass"
      >
        <el-table-column type="selection" width="44" :selectable="selectable" />
        <el-table-column label="插件" min-width="300">
          <template #default="{ row }">
            <div class="builtin-name">
              <div class="name">{{ row.name }}</div>
              <div class="desc">{{ row.description || row.id }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <el-tag :type="categoryTagType(row.category)" size="small" effect="light">
              {{ categoryLabel(row.category) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="平台" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="platformTagType(row.platform)">
              {{ platformLabel(row.platform) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.installed" type="success" size="small">已安装</el-tag>
            <el-tag v-else type="info" size="small">未安装</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="!row.installed"
              type="primary"
              size="small"
              link
              :loading="installingSingle === row.id"
              @click="onInstallSingle(row)"
            >
              安装
            </el-button>
            <span v-else class="installed-tip">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 批量安装结果 -->
    <el-card v-if="results.length" shadow="never" class="page-card results-card">
      <template #header>
        <div class="card-header">
          <span>安装结果（{{ results.filter(r => r.status === 'SUCCESS').length }}/{{ results.length }} 成功）</span>
          <el-button size="small" link @click="results = []">清除</el-button>
        </div>
      </template>
      <el-scrollbar max-height="160px">
        <div
          v-for="r in results"
          :key="r.pluginId || r.pluginName"
          class="result-item"
          :class="{ 'result-failed': r.status === 'FAILED' }"
        >
          <el-icon v-if="r.status === 'SUCCESS'" color="var(--platform-green)"><CircleCheckFilled /></el-icon>
          <el-icon v-else color="var(--platform-red)"><CircleCloseFilled /></el-icon>
          <span class="r-name">{{ r.pluginName }}</span>
          <span class="r-msg">{{ r.message }}</span>
        </div>
      </el-scrollbar>
    </el-card>

    <!-- 安装进度弹窗（单装/批装共用，等待直到任务终态） -->
    <TaskProgressDialog
      v-model:visible="installTaskVisible"
      :title="installTaskTitle"
      :poll-fn="pluginManageApi.installBuiltinStatus"
      :task-id="installTaskId"
      phase="task"
      :poll-interval-ms="TASK_POLL_INTERVAL_MS"
      @status="onInstallTaskStatus"
      @completed="onInstallTaskCompleted"
      @failed="onInstallTaskFailed"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pluginManageApi } from '@/api'
import type { BuiltinPluginVO, BuiltinInstallResultVO } from '@/api'
import TaskProgressDialog from '@/components/TaskProgressDialog.vue'
import type { TaskPollStatus } from '@/components/TaskProgressDialog.vue'
import { usePluginStore } from '@/stores/plugin'

const emit = defineEmits<{
  (e: 'plugins-changed'): void
  (e: 'install-progress', payload: { active: boolean; progress: number; title: string }): void
}>()

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)
const instanceDeployType = computed(() => store.instanceInfo?.deployType || '')

const loading = ref(false)
const list = ref<BuiltinPluginVO[]>([])
const keyword = ref('')
const categoryFilter = ref<'all' | 'platform' | 'required' | 'optional' | 'custom'>('all')
const installedFilter = ref<'all' | 'uninstalled' | 'installed'>('all')

const selectedRows = ref<BuiltinPluginVO[]>([])
const selectedIds = computed(() => selectedRows.value.map(r => r.id))
const selectedSize = computed(() => selectedRows.value.reduce((sum, r) => sum + (r.size || 0), 0))

const installing = ref(false)
const installingSingle = ref('')
const results = ref<BuiltinInstallResultVO[]>([])

/** 任务状态轮询间隔（毫秒）——进度弹窗内部同样按此节奏轮询 */
const TASK_POLL_INTERVAL_MS = 2000

const installTaskVisible = ref(false)
const installTaskTitle = ref('')
const installTaskId = ref('')
const installTaskMode = ref<'single' | 'batch'>('single')
const installTaskRow = ref<BuiltinPluginVO | null>(null)
const installTaskTotal = ref(0)

/**
 * 当前实例的平台过滤标签：docker 类部署返回 'linux'，native 部署根据浏览器/后端 OS 推断
 * 注意：前端无法准确知道后端 OS，这里简单按 deployType 判断，windows-only 插件在 docker 场景下隐藏。
 */
const instancePlatform = computed<'linux' | 'windows' | 'all'>(() => {
  const t = instanceDeployType.value.toLowerCase()
  if (t.includes('docker')) return 'linux'
  if (t.includes('native') || t.includes('standalone')) {
    // 简单推断：Windows 客户端用户多为 Windows 后端
    return navigator.platform.toLowerCase().includes('win') ? 'windows' : 'all'
  }
  return 'all'
})

const categoryOptions = computed(() => {
  const defs: Array<{ value: typeof categoryFilter.value; label: string }> = [
    { value: 'all', label: '全部' },
    { value: 'platform', label: '平台框架' },
    { value: 'required', label: '必选' },
    { value: 'optional', label: '可选' },
    { value: 'custom', label: '自选' },
  ]
  return defs.map(d => ({
    ...d,
    count: d.value === 'all'
      ? platformFiltered.value.length
      : platformFiltered.value.filter(p => p.category === d.value).length
  }))
})

/** 平台兼容过滤（docker 实例隐藏 windows-only，反之亦然） */
const platformFiltered = computed(() =>
  list.value.filter(p =>
    !(instancePlatform.value === 'linux' && p.platform === 'windows') &&
    !(instancePlatform.value === 'windows' && p.platform === 'linux')
  )
)

/** 关键词 + 分类 chip + 安装状态 chip 三重过滤后的行 */
const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return platformFiltered.value.filter(p => {
    if (categoryFilter.value !== 'all' && p.category !== categoryFilter.value) return false
    if (installedFilter.value === 'installed' && !p.installed) return false
    if (installedFilter.value === 'uninstalled' && p.installed) return false
    if (!kw) return true
    return p.name.toLowerCase().includes(kw) ||
      p.id.toLowerCase().includes(kw) ||
      (p.description || '').toLowerCase().includes(kw)
  })
})

function formatSize(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

function categoryLabel(c: string): string {
  return { platform: '平台框架', required: '必选', optional: '可选', custom: '自选' }[c] || c
}

function categoryTagType(c: string): 'danger' | 'warning' | 'success' | 'info' {
  return ({ platform: 'danger', required: 'warning', optional: 'success', custom: 'info' } as const)[c] || 'info'
}

function platformLabel(p: string): string {
  if (p === 'linux') return 'Linux'
  if (p === 'windows') return 'Windows'
  return '全平台'
}

function platformTagType(p: string): 'success' | 'warning' | 'info' {
  if (p === 'linux') return 'success'
  if (p === 'windows') return 'warning'
  return 'info'
}

function rowKey(row: BuiltinPluginVO): string {
  return row.id
}
function selectable(row: BuiltinPluginVO): boolean {
  return !row.installed
}
function rowClass({ row }: { row: BuiltinPluginVO }): string {
  return row.installed ? 'builtin-row-installed' : ''
}

async function loadList() {
  if (!instanceId.value) return
  loading.value = true
  try {
    const data = await pluginManageApi.listBuiltin(instanceId.value)
    list.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('加载内置插件列表失败：' + (e?.message || e))
    list.value = []
  } finally {
    loading.value = false
  }
}

function onSelectionChange(rows: BuiltinPluginVO[]) {
  selectedRows.value = rows
}

function onSelectAllUninstalled() {
  const uninstalled = filteredRows.value.filter(p => !p.installed)
  selectedRows.value = uninstalled
  ElMessage.success(`已选中 ${uninstalled.length} 个未安装插件`)
}

function onClearSelection() {
  selectedRows.value = []
}

async function onInstallSingle(row: BuiltinPluginVO) {
  if (!instanceId.value) return
  installingSingle.value = row.id
  try {
    const submit = await pluginManageApi.installBuiltin(instanceId.value, row.id)
    const taskId = submit?.taskId
    if (!taskId) {
      // 兼容：后端未返回 taskId 时直接视为成功
      onSingleInstallDone(row, row.name + ' 安装成功')
      return
    }
    installTaskMode.value = 'single'
    installTaskRow.value = row
    installTaskTitle.value = `安装插件：${row.name}`
    installTaskId.value = taskId
    installTaskVisible.value = true
    emitInstallProgress(true, 0, `安装插件：${row.name}`)
  } catch (e: any) {
    ElMessage.error(`${row.name} 提交安装任务失败：` + (e?.message || e))
    installingSingle.value = ''
  }
}

function emitInstallProgress(active: boolean, progress: number, title: string) {
  emit('install-progress', { active, progress, title })
}

/** 进度弹窗轮询到的安装任务状态 */
function onInstallTaskStatus(st: TaskPollStatus) {
  if (typeof st.progress === 'number') {
    emitInstallProgress(true, st.progress, installTaskTitle.value)
  }
}

function onInstallTaskCompleted(st: TaskPollStatus) {
  installTaskVisible.value = false
  emitInstallProgress(false, 100, '')
  if (installTaskMode.value === 'single') {
    const row = installTaskRow.value
    if (row) onSingleInstallDone(row, st.resultSummary || `${row.name} 安装成功`)
  } else {
    onBatchInstallDone(st, installTaskTotal.value)
  }
}

function onInstallTaskFailed(st: TaskPollStatus) {
  installTaskVisible.value = false
  emitInstallProgress(false, 100, '')
  const reason = st.errorMessage || st.resultSummary || st.status
  if (installTaskMode.value === 'single') {
    installingSingle.value = ''
    ElMessage.error(`${installTaskRow.value?.name || '插件'} 安装失败：` + reason)
  } else {
    installing.value = false
    if (st.result?.data?.results?.length) {
      results.value = st.result.data.results
    }
    ElMessage.error('批量安装' + (st.status === 'CANCELLED' ? '已取消' : '失败') + '：' + reason)
    loadList()
    emit('plugins-changed')
  }
}

function onSingleInstallDone(row: BuiltinPluginVO, msg: string) {
  ElMessage.success(msg)
  installingSingle.value = ''
  row.installed = true
  selectedRows.value = selectedRows.value.filter(r => r.id !== row.id)
  emit('plugins-changed')
}

async function onBatchInstall() {
  if (!instanceId.value || !selectedIds.value.length) return
  const ids = [...selectedIds.value]
  try {
    await ElMessageBox.confirm(
      `确认安装选中的 ${ids.length} 个内置插件？总大小约 ${formatSize(selectedSize.value)}。` +
      `安装任务将在后台执行，安装后请在"已安装"列表中点"启用"或应用预设使其生效。`,
      '批量安装确认',
      { type: 'info', confirmButtonText: '安装', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  installing.value = true
  results.value = []
  try {
    const submit = await pluginManageApi.batchInstallBuiltin({
      instanceId: instanceId.value,
      pluginIds: ids
    })
    const taskId = submit?.taskId
    if (!taskId) {
      // 兼容：后端未返回 taskId 时直接刷新状态
      await loadList()
      selectedRows.value = []
      emit('plugins-changed')
      return
    }
    ElMessage.success(`已提交批量安装任务（${ids.length} 个插件），后台执行中`)
    installTaskMode.value = 'batch'
    installTaskTotal.value = ids.length
    installTaskTitle.value = `批量安装 ${ids.length} 个插件`
    installTaskId.value = taskId
    installTaskVisible.value = true
    emitInstallProgress(true, 0, `批量安装 ${ids.length} 个插件`)
  } catch (e: any) {
    ElMessage.error('提交批量安装任务失败：' + (e?.message || e))
    installing.value = false
    emitInstallProgress(false, 0, '')
  }
}

async function onBatchInstallDone(st: { resultSummary?: string; result?: { data?: { total?: number; success?: number; failed?: number; results?: BuiltinInstallResultVO[] } } }, total: number) {
  const r = st.result?.data
  results.value = Array.isArray(r?.results) ? r!.results! : []
  const successCount = r?.success ?? total
  const failedCount = r?.failed ?? 0
  if (failedCount === 0) {
    ElMessage.success(st.resultSummary || `全部 ${successCount} 个插件安装成功`)
  } else {
    ElMessage.warning(`安装完成：${successCount} 成功，${failedCount} 失败，详见结果列表`)
  }
  await loadList()
  selectedRows.value = []
  emit('plugins-changed')
  installing.value = false
}

/** tab 首次激活时才拉取清单（外层用 v-if 挂载即触发 onMounted） */
onMounted(() => {
  loadList()
})

watch(instanceId, () => {
  if (instanceId.value) loadList()
})
</script>

<style lang="scss" scoped>
.builtin-tab {
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

.builtin-summary {
  font-size: 13px;
  color: var(--platform-text-secondary);
  white-space: nowrap;
}

.builtin-name {
  .name {
    font-weight: 500;
    color: var(--platform-text-primary);
  }

  .desc {
    font-size: 12px;
    color: var(--platform-text-secondary);
  }
}

.results-card {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
}

.result-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;

  .r-name {
    font-weight: 500;
    min-width: 180px;
  }

  .r-msg {
    color: var(--platform-text-secondary);
    font-size: 12px;
  }
}

.result-failed .r-name {
  color: var(--platform-red);
}

:deep(.builtin-row-installed) {
  .cell {
    color: var(--platform-text-secondary);
  }
}
</style>
