<template>
  <div class="plugins-page">
    <div class="page-header">
      <h1 class="page-title">插件管理</h1>
        <div class="header-actions">
          <el-button
            type="primary"
            @click="onOpenBuiltinMarket"
            :loading="loadingBuiltin"
          >
            <el-icon><Goods /></el-icon>
            插件市场
          </el-button>
          <el-button
            type="primary"
            @click="onInstallPlatform"
            :loading="installingPlatform"
          >
            <el-icon><Download /></el-icon>
            安装平台插件
          </el-button>
          <el-button type="primary" @click="showUploadDialog = true">
            <el-icon><Upload /></el-icon>
            上传插件
          </el-button>
          <el-button
            @click="onBatchEnable"
            :disabled="!selectedNames.length"
            :loading="batchEnabling"
          >
            批量启用
          </el-button>
          <el-button
            type="warning"
            @click="onBatchDisable"
            :disabled="!selectedNames.length"
            :loading="batchDisabling"
          >
            批量禁用
          </el-button>
          <el-button type="success" @click="onExportStart" :loading="exporting">
            全量导出
          </el-button>
          <el-button @click="refreshPlugins" :loading="loading">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="!loading && plugins.length === 0"
        type="warning"
        :closable="false"
        show-icon
        class="empty-alert"
      >
        <template #title>
          未检测到任何插件。镜像 laoyutang/l4d2-pure 不含 SourceMod 框架，
          请点击右上角"插件市场"按钮选择并安装内置插件包（含 SourceMod 1.11 + Metamod 平台框架）。
        </template>
      </el-alert>

      <el-card shadow="hover" class="plugins-card">
        <el-table
          :data="plugins"
          style="width: 100%"
          v-loading="loading"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="50" />
          <el-table-column prop="name" label="插件名称" min-width="200">
            <template #default="{ row }">
              <div class="plugin-name">
                <el-icon :size="18" style="margin-right: 8px"><Box /></el-icon>
                <div>
                  <div class="name">{{ row.name }}</div>
                  <div v-if="row.description" class="desc">{{ row.description }}</div>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="version" label="版本" width="100">
            <template #default="{ row }">{{ row.version || '-' }}</template>
          </el-table-column>

          <el-table-column prop="author" label="作者" width="140">
            <template #default="{ row }">{{ row.author || '-' }}</template>
          </el-table-column>

          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.status === 'enabled' ? 'success' : 'info'" size="small">
                {{ row.status === 'enabled' ? '已启用' : '已禁用' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status !== 'enabled'"
                type="primary"
                size="small"
                link
                @click="onEnable(row)"
              >
                启用
              </el-button>
              <el-button
                v-else
                type="warning"
                size="small"
                link
                @click="onDisable(row)"
              >
                禁用
              </el-button>
              <el-button type="primary" size="small" link @click="onConfig(row)">
                配置
              </el-button>
              <el-button type="danger" size="small" link @click="onDelete(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-dialog v-model="showUploadDialog" title="上传插件" width="500px">
        <el-upload
          drag
          :auto-upload="false"
          :on-change="handleFileChange"
          :limit="1"
          accept=".smx,.zip,.7z,.vpk"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">
            将插件文件拖到此处，或<em>点击上传</em>
          </div>
          <template #tip>
            <div class="el-upload__tip">
              支持 .smx / .zip / .7z / .vpk 格式
            </div>
          </template>
        </el-upload>
        <el-progress
          v-if="uploading && uploadPercent > 0"
          :percentage="uploadPercent"
          :stroke-width="6"
          style="margin-top: 12px"
        />
        <template #footer>
          <el-button @click="showUploadDialog = false">取消</el-button>
          <el-button type="primary" @click="uploadPlugin" :loading="uploading">
            上传
          </el-button>
        </template>
      </el-dialog>

      <el-dialog
        v-model="showExportDialog"
        title="全量导出"
        width="520px"
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        :show-close="exportStatus !== 'RUNNING'"
      >
        <div class="export-content">
          <el-progress
            v-if="exportStatus === 'RUNNING'"
            :percentage="exportPercent"
            :stroke-width="10"
            status="success"
          />
          <div v-if="exportStatus === 'RUNNING'" class="export-tip">
            正在打包... {{ exportProcessed }} / {{ exportTotal }} 文件
          </div>
          <el-result
            v-else-if="exportStatus === 'COMPLETED'"
            icon="success"
            title="导出完成"
            sub-title="点击下方按钮下载 ZIP 文件"
          />
          <el-result
            v-else-if="exportStatus === 'FAILED'"
            icon="error"
            title="导出失败"
            :sub-title="exportError || '请稍后重试'"
          />
          <el-result
            v-else-if="exportStatus === 'CANCELLED'"
            icon="warning"
            title="已取消"
            sub-title="导出任务已被取消"
          />
        </div>
        <template #footer>
          <el-button
            v-if="exportStatus === 'RUNNING'"
            type="danger"
            @click="onExportCancel"
          >
            取消导出
          </el-button>
          <el-button
            v-if="exportStatus === 'COMPLETED' && instanceId"
            type="primary"
            @click="onExportDownload"
          >
            下载 ZIP
          </el-button>
          <el-button
            v-if="exportStatus !== 'RUNNING'"
            @click="showExportDialog = false"
          >
            关闭
          </el-button>
        </template>
      </el-dialog>

      <!-- 内置插件市场对话框 -->
      <el-dialog
        v-model="showBuiltinDialog"
        title="内置插件市场"
        width="960px"
        top="5vh"
        :close-on-click-modal="false"
        :close-on-press-escape="!installingBuiltin"
        :show-close="!installingBuiltin"
      >
        <div class="builtin-market">
          <!-- 顶部工具栏 -->
          <div class="builtin-toolbar">
            <el-input
              v-model="builtinKeyword"
              placeholder="搜索插件名/描述"
              clearable
              size="small"
              style="width: 220px"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-button-group size="small">
              <el-button @click="onSelectCategory('required')">全选必选</el-button>
              <el-button @click="onSelectAllVisible">全选可见</el-button>
              <el-button @click="onSelectAllUninstalled">全选未安装</el-button>
              <el-button @click="onClearSelection">清空选择</el-button>
            </el-button-group>
            <div class="builtin-summary">
              <span>已选 <b>{{ selectedBuiltinIds.length }}</b> 个</span>
              <span>合计 <b>{{ formatSize(selectedBuiltinSize) }}</b></span>
            </div>
          </div>

          <!-- 进度条 -->
          <el-progress
            v-if="installingBuiltin"
            :percentage="builtinInstallProgress"
            :stroke-width="8"
            status="success"
            style="margin: 8px 0"
          />

          <!-- 分类折叠面板 -->
          <el-collapse v-model="activeCollapse" class="builtin-collapse">
            <el-collapse-item
              v-for="cat in categoryGroups"
              :key="cat.key"
              :name="cat.key"
              :title="`${cat.label}（${cat.items.length}）`"
            >
              <template #title>
                <div class="collapse-title">
                  <el-tag :type="cat.tagType" size="small" effect="dark">{{ cat.label }}</el-tag>
                  <span class="collapse-count">{{ cat.items.length }} 个</span>
                  <span v-if="cat.installedCount > 0" class="collapse-installed">
                    （已安装 {{ cat.installedCount }}）
                  </span>
                </div>
              </template>
              <el-table
                :data="cat.items"
                size="small"
                :row-key="builtinRowKey"
                @selection-change="onBuiltinSelectionChange"
                :row-class-name="builtinRowClass"
              >
                <el-table-column type="selection" width="40" :selectable="builtinSelectable" />
                <el-table-column label="插件" min-width="280">
                  <template #default="{ row }">
                    <div class="builtin-name">
                      <div class="name">{{ row.name }}</div>
                      <div class="desc">{{ row.description || row.id }}</div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="平台" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" :type="platformTagType(row.platform)">
                      {{ platformLabel(row.platform) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="大小" width="90">
                  <template #default="{ row }">{{ formatSize(row.size) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="100">
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
            </el-collapse-item>
          </el-collapse>

          <!-- 批量安装结果 -->
          <div v-if="builtinResults.length" class="builtin-results">
            <div class="results-header">
              安装结果（{{ builtinResults.filter(r => r.status === 'SUCCESS').length }}/{{ builtinResults.length }} 成功）
            </div>
            <el-scrollbar max-height="160px">
              <div
                v-for="r in builtinResults"
                :key="r.pluginId || r.pluginName"
                class="result-item"
                :class="{ 'result-failed': r.status === 'FAILED' }"
              >
                <el-icon v-if="r.status === 'SUCCESS'" color="#67c23a"><CircleCheckFilled /></el-icon>
                <el-icon v-else color="#f56c6c"><CircleCloseFilled /></el-icon>
                <span class="r-name">{{ r.pluginName }}</span>
                <span class="r-msg">{{ r.message }}</span>
              </div>
            </el-scrollbar>
          </div>
        </div>

        <template #footer>
          <el-button @click="showBuiltinDialog = false" :disabled="installingBuiltin">关闭</el-button>
          <el-button
            type="primary"
            :loading="installingBuiltin"
            :disabled="!selectedBuiltinIds.length || installingBuiltin"
            @click="onBatchInstallBuiltin"
          >
            安装选中 {{ selectedBuiltinIds.length }} 个
          </el-button>
        </template>
      </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pluginManageApi } from '@/api'
import type { PluginListVO, BuiltinPluginVO, BuiltinInstallResultVO } from '@/api'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const router = useRouter()
const instanceId = computed(() => store.instanceInfo?.instanceId)
const instanceDeployType = computed(() => store.instanceInfo?.deployType || '')

const loading = ref(false)
const plugins = ref<PluginListVO[]>([])

const showUploadDialog = ref(false)
const uploadFile = ref<File | null>(null)
const uploading = ref(false)
const uploadPercent = ref(0)

const selectedNames = ref<string[]>([])
const batchEnabling = ref(false)
const batchDisabling = ref(false)

// 内置平台插件（SourceMod + Metamod）安装状态
const installingPlatform = ref(false)

const showExportDialog = ref(false)
const exporting = ref(false)
const exportStatus = ref<'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'>('IDLE')
const exportTotal = ref(0)
const exportProcessed = ref(0)
const exportError = ref('')
let exportTimer: ReturnType<typeof setInterval> | null = null

const exportPercent = computed(() => {
  if (!exportTotal.value) return 0
  return Math.min(100, Math.round((exportProcessed.value / exportTotal.value) * 100))
})

// ========== 内置插件市场 ==========
const showBuiltinDialog = ref(false)
const loadingBuiltin = ref(false)
const builtinList = ref<BuiltinPluginVO[]>([])
const builtinKeyword = ref('')
/** 当前选中的插件行（来自所有分类表格的 selection-change 事件，去重合并） */
const selectedBuiltinRows = ref<BuiltinPluginVO[]>([])
/** 当前选中的插件 ID 列表（与 selectedBuiltinRows 同步，用于提交批量安装） */
const selectedBuiltinIds = computed(() => selectedBuiltinRows.value.map(r => r.id))
const installingBuiltin = ref(false)
const installingSingle = ref<string>('')
const builtinResults = ref<BuiltinInstallResultVO[]>([])
const builtinInstallProgress = ref(0)
/** 默认展开所有分类 */
const activeCollapse = ref<string[]>(['platform', 'required', 'optional', 'custom'])

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

/**
 * 关键词过滤后的内置插件列表。
 */
const filteredBuiltinList = computed(() => {
  const kw = builtinKeyword.value.trim().toLowerCase()
  if (!kw) return builtinList.value
  return builtinList.value.filter(p =>
    p.name.toLowerCase().includes(kw) ||
    p.id.toLowerCase().includes(kw) ||
    (p.description || '').toLowerCase().includes(kw)
  )
})

interface CategoryGroup {
  key: 'platform' | 'required' | 'optional' | 'custom'
  label: string
  tagType: 'danger' | 'warning' | 'success' | 'info'
  items: BuiltinPluginVO[]
  installedCount: number
}

/**
 * 按分类分组并过滤当前实例不兼容的插件（如 docker 实例隐藏 windows-only）。
 */
const categoryGroups = computed<CategoryGroup[]>(() => {
  const groups: CategoryGroup[] = [
    { key: 'platform', label: '平台框架', tagType: 'danger', items: [], installedCount: 0 },
    { key: 'required', label: '必选插件', tagType: 'warning', items: [], installedCount: 0 },
    { key: 'optional', label: '可选插件', tagType: 'success', items: [], installedCount: 0 },
    { key: 'custom', label: '自选插件', tagType: 'info', items: [], installedCount: 0 },
  ]
  const plat = instancePlatform.value
  for (const p of filteredBuiltinList.value) {
    // 平台过滤：当前实例为 linux 时隐藏 windows-only，反之亦然
    if (plat === 'linux' && p.platform === 'windows') continue
    if (plat === 'windows' && p.platform === 'linux') continue
    const g = groups.find(x => x.key === p.category)
    if (!g) continue
    g.items.push(p)
    if (p.installed) g.installedCount++
  }
  return groups
})

const selectedBuiltinSize = computed(() => {
  return selectedBuiltinRows.value.reduce((sum, r) => sum + (r.size || 0), 0)
})

function formatSize(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

function platformLabel(p: string): string {
  if (p === 'linux') return 'Linux'
  if (p === 'windows') return 'Windows'
  return '全平台'
}

// el-table 行 key/状态判定辅助函数（Vue 模板中无法直接写 TS 类型注解）
function builtinRowKey(row: BuiltinPluginVO): string {
  return row.id
}
function builtinSelectable(row: BuiltinPluginVO): boolean {
  return !row.installed
}
function builtinRowClass({ row }: { row: BuiltinPluginVO }): string {
  return row.installed ? 'builtin-row-installed' : ''
}

function platformTagType(p: string): 'success' | 'warning' | 'info' {
  if (p === 'linux') return 'success'
  if (p === 'windows') return 'warning'
  return 'info'
}

async function onOpenBuiltinMarket() {
  console.log('[Plugins] onOpenBuiltinMarket called, instanceId=', instanceId.value)
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  showBuiltinDialog.value = true
  builtinResults.value = []
  selectedBuiltinRows.value = []
  await loadBuiltinList()
}

async function loadBuiltinList() {
  if (!instanceId.value) return
  loadingBuiltin.value = true
  try {
    console.log('[Plugins] loadBuiltinList start, instanceId=', instanceId.value)
    const data = await pluginManageApi.listBuiltin(instanceId.value)
    console.log('[Plugins] loadBuiltinList data length=', Array.isArray(data) ? data.length : 'not array', data)
    builtinList.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    console.error('[Plugins] loadBuiltinList error:', e)
    ElMessage.error('加载内置插件列表失败：' + (e?.message || e))
    builtinList.value = []
  } finally {
    loadingBuiltin.value = false
  }
}

/**
 * 表格 selection-change 事件：每个分类表格独立触发，需要合并去重。
 * 由于同一插件只会在一个分类中出现，按 id 去重即可。
 */
function onBuiltinSelectionChange(rows: BuiltinPluginVO[]) {
  // 先移除当前分类表格中之前选中的行（即属于本次触发表格的分类的行）
  // 简化处理：直接以最新触发的 rows 为准，重新合并所有表格选中状态
  // 但 Element Plus 表格的 selection-change 是每个表格独立触发的，
  // 我们无法直接知道是哪个分类触发的。采用按 id 集合合并的方式：
  // 1. 收集本次 rows 的 id 集合
  // 2. 移除 selectedBuiltinRows 中分类与本次 rows 中任一插件分类相同的项
  // 3. 合并本次 rows
  if (!rows.length) {
    // 无法判断是哪个表格清空的，这里简化：仅当所有表格都为空时才清空
    // 实际上 Element Plus 每个表格独立管理 selection，清空某个表格时 rows=[]
    // 此处采用保守策略：不清空（用户可通过"清空选择"按钮主动清空）
    return
  }
  const newCategory = rows[0].category
  const kept = selectedBuiltinRows.value.filter(r => r.category !== newCategory)
  selectedBuiltinRows.value = [...kept, ...rows]
}

function onSelectCategory(category: 'required' | 'optional' | 'custom') {
  const items = builtinList.value.filter(p =>
    p.category === category && !p.installed &&
    // 平台过滤
    !(instancePlatform.value === 'linux' && p.platform === 'windows') &&
    !(instancePlatform.value === 'windows' && p.platform === 'linux')
  )
  // 合并：保留其他分类已选项，追加本分类所有未安装项
  const kept = selectedBuiltinRows.value.filter(r => r.category !== category)
  // 按 id 去重
  const existingIds = new Set(kept.map(r => r.id))
  const toAdd = items.filter(p => !existingIds.has(p.id))
  selectedBuiltinRows.value = [...kept, ...toAdd]
  ElMessage.success(`已选中 ${items.length} 个 ${category === 'required' ? '必选' : category === 'optional' ? '可选' : '自选'} 插件`)
}

function onSelectAllVisible() {
  const all = categoryGroups.value.flatMap(g => g.items).filter(p => !p.installed)
  selectedBuiltinRows.value = all
  ElMessage.success(`已选中 ${all.length} 个未安装插件`)
}

function onSelectAllUninstalled() {
  const uninstalled = builtinList.value.filter(p => !p.installed &&
    !(instancePlatform.value === 'linux' && p.platform === 'windows') &&
    !(instancePlatform.value === 'windows' && p.platform === 'linux')
  )
  selectedBuiltinRows.value = uninstalled
  ElMessage.success(`已选中 ${uninstalled.length} 个未安装插件`)
}

function onClearSelection() {
  selectedBuiltinRows.value = []
}

async function onInstallSingle(row: BuiltinPluginVO) {
  if (!instanceId.value) return
  installingSingle.value = row.id
  try {
    const msg = await pluginManageApi.installBuiltin(instanceId.value, row.id)
    ElMessage.success(msg || `${row.name} 安装成功`)
    // 更新本地状态
    row.installed = true
    // 从选中列表中移除（已安装的不能再次选中）
    selectedBuiltinRows.value = selectedBuiltinRows.value.filter(r => r.id !== row.id)
    // 刷新主列表
    refreshPlugins()
  } catch (e: any) {
    ElMessage.error(`${row.name} 安装失败：` + (e?.message || e))
  } finally {
    installingSingle.value = ''
  }
}

async function onBatchInstallBuiltin() {
  if (!instanceId.value || !selectedBuiltinIds.value.length) return
  const ids = [...selectedBuiltinIds.value]
  try {
    await ElMessageBox.confirm(
      `确认安装选中的 ${ids.length} 个内置插件？总大小约 ${formatSize(selectedBuiltinSize.value)}。` +
      `安装后请在插件列表中点"启用"或应用预设使其生效。`,
      '批量安装确认',
      { type: 'info', confirmButtonText: '安装', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  installingBuiltin.value = true
  builtinResults.value = []
  builtinInstallProgress.value = 0
  try {
    // 调用批量安装接口（后端按顺序执行，单个失败不影响其他）
    const results = await pluginManageApi.batchInstallBuiltin({
      instanceId: instanceId.value,
      pluginIds: ids
    })
    builtinResults.value = Array.isArray(results) ? results : []
    // 模拟进度条满
    builtinInstallProgress.value = 100

    const successCount = builtinResults.value.filter(r => r.status === 'SUCCESS').length
    const failedCount = builtinResults.value.length - successCount
    if (failedCount === 0) {
      ElMessage.success(`全部 ${successCount} 个插件安装成功`)
    } else {
      ElMessage.warning(`安装完成：${successCount} 成功，${failedCount} 失败，详见下方结果`)
    }

    // 刷新内置列表的 installed 状态
    await loadBuiltinList()
    // 清空选中（已安装的会被 selectable 过滤）
    selectedBuiltinRows.value = []
    // 刷新主插件列表
    refreshPlugins()
  } catch (e: any) {
    ElMessage.error('批量安装失败：' + (e?.message || e))
  } finally {
    installingBuiltin.value = false
  }
}

async function refreshPlugins() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  loading.value = true
  try {
    const data = await pluginManageApi.list(instanceId.value)
    plugins.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('获取插件列表失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function onSelectionChange(rows: PluginListVO[]) {
  selectedNames.value = rows.map(r => r.name)
}

function handleFileChange(file: any) {
  uploadFile.value = file.raw
  uploadPercent.value = 0
}

async function uploadPlugin() {
  if (!uploadFile.value) {
    ElMessage.warning('请选择文件')
    return
  }
  if (!instanceId.value) return
  uploading.value = true
  try {
    await pluginManageApi.upload(uploadFile.value, instanceId.value, (percent) => {
      uploadPercent.value = percent
    })
    ElMessage.success('插件已上传')
    showUploadDialog.value = false
    uploadFile.value = null
    uploadPercent.value = 0
    refreshPlugins()
  } catch (e: any) {
    ElMessage.error('上传失败：' + (e?.message || e))
  } finally {
    uploading.value = false
  }
}

async function onEnable(row: PluginListVO) {
  if (!instanceId.value) return
  try {
    await pluginManageApi.enableLoad(instanceId.value, row.name)
    ElMessage.success(`插件 "${row.name}" 已启用并加载`)
    refreshPlugins()
  } catch (e: any) {
    ElMessage.error('启用失败：' + (e?.message || e))
  }
}

/**
 * 安装内置平台插件包（SourceMod 1.11 + Metamod）。
 *
 * 镜像 laoyutang/l4d2-pure 不含 SourceMod，需先安装平台插件包才能加载其他 .smx 插件。
 * 安装后插件出现在列表中（状态为已禁用），需用户手动点"启用"或通过应用预设启用。
 */
async function onInstallPlatform() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  try {
    await ElMessageBox.confirm(
      '将向容器部署内置 SourceMod 1.11 + Metamod 平台插件包（约 63MB），' +
      '部署后请点击"启用"或应用预设使其生效。是否继续？',
      '安装平台插件',
      { type: 'info', confirmButtonText: '安装', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  installingPlatform.value = true
  try {
    const msg = await pluginManageApi.installPlatform(instanceId.value)
    ElMessage.success(msg || '平台插件安装成功')
    refreshPlugins()
  } catch (e: any) {
    ElMessage.error('平台插件安装失败：' + (e?.message || e))
  } finally {
    installingPlatform.value = false
  }
}

async function onDisable(row: PluginListVO) {
  if (!instanceId.value) return
  try {
    await ElMessageBox.confirm(
      `确认禁用插件 "${row.name}"？将通过 RCON 卸载并移动到 disabled 目录。`,
      '确认禁用',
      { type: 'warning', confirmButtonText: '禁用', cancelButtonText: '取消' }
    )
    await pluginManageApi.disableUnload(instanceId.value, row.name)
    ElMessage.success(`插件 "${row.name}" 已禁用`)
    refreshPlugins()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('禁用失败：' + (e?.message || e))
  }
}

function onConfig(row: PluginListVO) {
  router.push({ path: '/plugin-config', query: { pluginName: row.name } })
}

async function onDelete(row: PluginListVO) {
  if (!instanceId.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除插件 "${row.name}"？此操作不可恢复。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await pluginManageApi.delete(instanceId.value, row.name)
    ElMessage.success('已删除')
    refreshPlugins()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message || e))
  }
}

async function onBatchEnable() {
  if (!selectedNames.value.length || !instanceId.value) return
  try {
    await ElMessageBox.confirm(
      `确认批量启用 ${selectedNames.value.length} 个插件？`,
      '确认批量启用',
      { type: 'warning', confirmButtonText: '启用', cancelButtonText: '取消' }
    )
    batchEnabling.value = true
    await pluginManageApi.batchEnable({
      instanceId: instanceId.value,
      pluginNames: selectedNames.value
    })
    ElMessage.success('批量启用已完成')
    refreshPlugins()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('批量启用失败：' + (e?.message || e))
  } finally {
    batchEnabling.value = false
  }
}

async function onBatchDisable() {
  if (!selectedNames.value.length || !instanceId.value) return
  try {
    await ElMessageBox.confirm(
      `确认批量禁用 ${selectedNames.value.length} 个插件？`,
      '确认批量禁用',
      { type: 'warning', confirmButtonText: '禁用', cancelButtonText: '取消' }
    )
    batchDisabling.value = true
    await pluginManageApi.batchDisable({
      instanceId: instanceId.value,
      pluginNames: selectedNames.value
    })
    ElMessage.success('批量禁用已完成')
    refreshPlugins()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('批量禁用失败：' + (e?.message || e))
  } finally {
    batchDisabling.value = false
  }
}

async function onExportStart() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  exporting.value = true
  try {
    await pluginManageApi.exportAllStart(instanceId.value)
    exportStatus.value = 'RUNNING'
    exportTotal.value = 0
    exportProcessed.value = 0
    exportError.value = ''
    showExportDialog.value = true
    startExportPolling()
  } catch (e: any) {
    ElMessage.error('启动导出失败：' + (e?.message || e))
  } finally {
    exporting.value = false
  }
}

function startExportPolling() {
  stopExportPolling()
  exportTimer = setInterval(refreshExportStatus, 1000)
}

function stopExportPolling() {
  if (exportTimer) {
    clearInterval(exportTimer)
    exportTimer = null
  }
}

async function refreshExportStatus() {
  if (!instanceId.value) return
  try {
    const data = await pluginManageApi.exportAllStatus(instanceId.value)
    exportStatus.value = (data.status as typeof exportStatus.value) || 'RUNNING'
    exportTotal.value = data.totalFiles || 0
    exportProcessed.value = data.processedFiles || 0
    exportError.value = data.error || ''
    if (data.status !== 'RUNNING') {
      stopExportPolling()
    }
  } catch (e: any) {
    exportError.value = e?.message || String(e)
    exportStatus.value = 'FAILED'
    stopExportPolling()
  }
}

async function onExportCancel() {
  if (!instanceId.value) return
  try {
    await pluginManageApi.exportAllCancel(instanceId.value)
    ElMessage.success('已请求取消')
  } catch (e: any) {
    ElMessage.error('取消失败：' + (e?.message || e))
  }
}

function onExportDownload() {
  if (!instanceId.value) return
  const url = pluginManageApi.exportAllDownloadUrl(instanceId.value)
  window.open(url, '_blank')
}

onMounted(() => {
  refreshPlugins()
})

onBeforeUnmount(() => {
  stopExportPolling()
})
</script>

<style lang="scss" scoped>
.plugins-page {
  height: 100%;
  overflow-y: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.plugins-card {
  .plugin-name {
    display: flex;
    align-items: center;

    .name {
      font-weight: 500;
    }

    .desc {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
}

.export-content {
  padding: 12px 0;
}

.export-tip {
  margin-top: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
