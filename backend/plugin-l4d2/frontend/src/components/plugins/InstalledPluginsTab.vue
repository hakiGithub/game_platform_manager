<template>
  <div class="installed-tab">
    <div class="tab-toolbar">
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
      <el-button @click="$emit('refresh')" :loading="loading">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <el-card shadow="never" class="page-card">
      <el-table
        :data="plugins"
        style="width: 100%"
        v-loading="loading"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column prop="name" label="插件名称" min-width="220">
          <template #default="{ row }">
            <div class="plugin-name" @click="openDetail(row)">
              <el-icon :size="18" style="margin-right: 8px"><Box /></el-icon>
              <div>
                <div class="name detail-link">{{ row.name }}</div>
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

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="status-cell">
              <span
                class="status-dot"
                :class="row.status === 'enabled' ? 'running' : 'stopped'"
              ></span>
              {{ row.status === 'enabled' ? '已启用' : '已禁用' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
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

    <!-- 已安装插件详情抽屉（ADR-0022 决策 5） -->
    <el-drawer
      v-model="showDetail"
      :title="detailRow?.name || '插件详情'"
      size="420px"
      direction="rtl"
    >
      <div v-if="detailRow" class="installed-detail">
        <div class="detail-rows">
          <div class="d-row">
            <span class="d-label">状态</span>
            <span>{{ detailRow.status === 'enabled' ? '已启用' : '已禁用' }}</span>
          </div>
          <div class="d-row">
            <span class="d-label">版本</span>
            <span>{{ detailRow.version || '-' }}</span>
          </div>
          <div class="d-row">
            <span class="d-label">作者</span>
            <span>{{ detailRow.author || '-' }}</span>
          </div>
          <div class="d-row">
            <span class="d-label">来源</span>
            <span>{{ detailRow.source || '-' }}</span>
          </div>
          <div class="d-row">
            <span class="d-label">更新时间</span>
            <span>{{ detailRow.updateTime || detailRow.createTime || '-' }}</span>
          </div>
        </div>
        <div v-if="detailRow.description" class="detail-desc">
          {{ detailRow.description }}
        </div>

        <el-divider content-position="left">文件</el-divider>
        <div v-if="detailRow.fileList?.length" class="file-list">
          <div v-for="f in detailRow.fileList" :key="f" class="file-item">{{ f }}</div>
        </div>
        <div v-else class="file-empty">无文件清单</div>

        <div class="detail-actions">
          <el-button
            v-if="detailRow.status !== 'enabled'"
            type="primary"
            @click="onEnable(detailRow)"
          >
            启用
          </el-button>
          <el-button v-else type="warning" @click="onDisable(detailRow)">
            禁用
          </el-button>
          <el-button @click="onConfig(detailRow)">配置</el-button>
          <el-button type="danger" @click="onDelete(detailRow)">删除</el-button>
        </div>
      </div>
    </el-drawer>

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
        <el-button v-if="exportStatus !== 'RUNNING'" @click="showExportDialog = false">
          关闭
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pluginManageApi } from '@/api'
import type { PluginListVO } from '@/api'
import { usePluginStore } from '@/stores/plugin'

const props = defineProps<{
  plugins: PluginListVO[]
  loading: boolean
}>()
void props

const emit = defineEmits<{
  (e: 'refresh'): void
}>()

const store = usePluginStore()
const router = useRouter()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const showUploadDialog = ref(false)
const uploadFile = ref<File | null>(null)
const uploading = ref(false)
const uploadPercent = ref(0)

const selectedNames = ref<string[]>([])
const batchEnabling = ref(false)
const batchDisabling = ref(false)

const installingPlatform = ref(false)

const showDetail = ref(false)
const detailRow = ref<PluginListVO | null>(null)

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

function openDetail(row: PluginListVO) {
  detailRow.value = row
  showDetail.value = true
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
    emit('refresh')
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
    emit('refresh')
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
    emit('refresh')
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
    showDetail.value = false
    emit('refresh')
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
    showDetail.value = false
    emit('refresh')
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
    emit('refresh')
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
    emit('refresh')
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

onBeforeUnmount(() => {
  stopExportPolling()
})
</script>

<style lang="scss" scoped>
.installed-tab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tab-toolbar {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.plugin-name {
  display: flex;
  align-items: center;
  cursor: pointer;

  .name {
    font-weight: 500;
    color: var(--platform-text-primary);
  }

  .desc {
    font-size: 12px;
    color: var(--platform-text-secondary);
  }
}

.detail-link:hover {
  color: var(--el-color-primary);
}

.installed-detail {
  .detail-rows {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-bottom: 12px;
  }

  .d-row {
    display: flex;
    gap: 12px;
    font-size: 13px;
  }

  .d-label {
    width: 72px;
    flex-shrink: 0;
    color: var(--platform-text-secondary);
  }

  .detail-desc {
    font-size: 13px;
    color: var(--platform-text-secondary);
    line-height: 1.6;
  }

  .file-list {
    max-height: 240px;
    overflow-y: auto;
    font-size: 12px;
    font-family: monospace;
    color: var(--platform-text-secondary);
  }

  .file-item {
    padding: 2px 0;
  }

  .file-empty {
    font-size: 13px;
    color: var(--platform-text-secondary);
  }

  .detail-actions {
    margin-top: 20px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
}

.export-content {
  padding: 12px 0;
}

.export-tip {
  margin-top: 12px;
  font-size: 13px;
  color: var(--platform-text-secondary);
  text-align: center;
}
</style>
