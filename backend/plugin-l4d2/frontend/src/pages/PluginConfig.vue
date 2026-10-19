<template>
  <div class="plugin-config-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / PLUGIN CONFIG</span>
        <h2>插件配置</h2>
        <p>在线编辑 SourceMod 插件 cfg 配置项</p>
      </div>
      <div class="header-actions">
        <el-select
          v-model="selectedPlugin"
          placeholder="选择插件"
          filterable
          style="width: 280px"
          @change="onPluginChange"
        
        >
          <el-option
            v-for="p in plugins"
            :key="p.name"
            :label="p.name"
            :value="p.name"
          />
        </el-select>
        <el-button @click="loadPlugins" :loading="pluginsLoading">刷新插件</el-button>
        <el-button @click="showCandidates = true" :disabled="!selectedPlugin">
          查看候选路径
        </el-button>
        <el-button type="warning" plain @click="onRestoreDefaults" :disabled="!selectedPlugin">
          恢复默认配置
        </el-button>
      </div>
    </div>

    <el-card v-loading="loading" class="page-card" shadow="never">
      <div v-if="!selectedPlugin" class="empty-tip">
        <el-empty description="请先选择插件" />
      </div>

      <div v-else>
        <!-- 插件无候选 cfg 文件 -->
        <div v-if="config && !configFiles.length" class="empty-tip">
          <el-empty
            description="未找到该插件的 cfg 配置文件——插件可能没有可编辑的配置项，或尚未安装/启用"
          />
        </div>

        <el-collapse
          v-if="configFiles.length"
          v-model="expandedFiles"
          class="config-file-groups"
        >
          <el-collapse-item
            v-for="file in configFiles"
            :key="file.fileId"
            :name="file.fileId"
          >
            <template #title>
              <div class="config-file-title">
                <span class="file-name">{{ file.configName }}</span>
                <el-tag :type="fileStatusType(file.status)" size="small">
                  {{ fileStatusLabel(file.status) }}
                </el-tag>
                <span class="file-path">{{ file.configPath }}</span>
                <span v-if="file.lastSyncedAt" class="synced">
                  最后同步: {{ formatTime(file.lastSyncedAt) }}
                </span>
              </div>
            </template>

            <el-alert
              v-if="file.error"
              :title="file.error"
              :type="file.status === 'ERROR' ? 'error' : 'info'"
              :closable="false"
              show-icon
              class="file-status-alert"
            />

            <el-table :data="file.items" stripe size="small">
              <el-table-column prop="key" label="配置项 Key" min-width="180" />
              <el-table-column label="当前值" min-width="220">
                <template #default="{ row }">
                  <el-input
                    v-model="row.value"
                    size="small"
                    :disabled="!canEditFile(file)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="默认值" min-width="160">
                <template #default="{ row }">
                  <span class="muted">{{ row.defaultValue || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="范围" width="140">
                <template #default="{ row }">
                  <span v-if="row.min !== undefined || row.max !== undefined" class="muted">
                    {{ row.min ?? '-' }} ~ {{ row.max ?? '-' }}
                  </span>
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="描述" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="muted">{{ row.description || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="100" fixed="right">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    size="small"
                    :loading="applyingTempKey === `${file.fileId}:${row.key}`"
                    :disabled="!canApplyTemp(file, row)"
                    :title="tempDisabledReason(file, row)"
                    @click="onApplyTemp(file, row)"
                  >
                    临时设置
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="footer-actions">
              <el-button
                @click="onResetDefaults(file)"
                :disabled="!canEditFile(file)"
              >
                还原默认值
              </el-button>
              <el-button
                type="primary"
                @click="onSave(file)"
                :loading="savingFileId === file.fileId"
                :disabled="!canEditFile(file)"
              >
                保存
              </el-button>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-card>

    <el-dialog v-model="showCandidates" title="候选 cfg 文件路径" width="600px">
      <div v-loading="candidatesLoading">
        <el-table :data="candidates" stripe size="small">
          <el-table-column prop="path" label="路径" min-width="380" />
          <el-table-column label="存在" width="100">
            <template #default="{ row }">
              <el-tag :type="row.exists ? 'success' : 'info'" size="small">
                {{ row.exists ? '存在' : '不存在' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="showCandidates = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pluginConfigApi, pluginManageApi } from '@/api'
import type { PluginListVO } from '@/api'
import { usePluginStore } from '@/stores/plugin'
import {
  normalizeConfigFiles,
  fileStatusLabel,
  fileStatusType,
  resetFileDefaults,
  canEditConfigFile,
  tempConfigDisabledReason
} from '@/utils/pluginConfigFiles'
import type { PluginConfigFile, PluginConfigItem, PluginConfigPayload } from '@/utils/pluginConfigFiles'

const route = useRoute()
const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const plugins = ref<PluginListVO[]>([])
const pluginsLoading = ref(false)
const selectedPlugin = ref('')
const loading = ref(false)
const savingFileId = ref('')
const applyingTempKey = ref('')
let configRequestId = 0

const config = ref<PluginConfigPayload | null>(null)
const configFiles = ref<PluginConfigFile[]>([])
const expandedFiles = ref<string[]>([])

const showCandidates = ref(false)
const candidatesLoading = ref(false)
const candidates = ref<Array<{ path: string; exists: boolean }>>([])

watch(instanceId, () => {
  loadPlugins()
})

async function loadPlugins() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  pluginsLoading.value = true
  try {
    const data = await pluginManageApi.list(instanceId.value, { page: 1, size: 100 })
    plugins.value = Array.isArray(data?.records) ? data.records : []
    const fromQuery = route.query.pluginName
    if (fromQuery && typeof fromQuery === 'string') {
      selectedPlugin.value = fromQuery
      onPluginChange(fromQuery)
    }
  } catch (e: any) {
    ElMessage.error('加载插件列表失败：' + (e?.message || e))
  } finally {
    pluginsLoading.value = false
  }
}

async function onPluginChange(name: string) {
  const requestId = ++configRequestId
  if (!name || !instanceId.value) {
    config.value = null
    configFiles.value = []
    expandedFiles.value = []
    return
  }
  loading.value = true
  try {
    const data = await pluginConfigApi.get(instanceId.value, name)
    if (requestId !== configRequestId || selectedPlugin.value !== name) return
    config.value = data || null
    configFiles.value = normalizeConfigFiles(data)
    expandedFiles.value = configFiles.value
      .filter(file => file.status === 'OK' || file.status === 'EMPTY')
      .map(file => file.fileId)
  } catch (e: any) {
    if (requestId !== configRequestId || selectedPlugin.value !== name) return
    ElMessage.error('加载配置失败：' + (e?.message || e))
    config.value = null
    configFiles.value = []
    expandedFiles.value = []
  } finally {
    if (requestId === configRequestId) loading.value = false
  }
}

async function loadCandidates() {
  if (!selectedPlugin.value || !instanceId.value) return
  candidatesLoading.value = true
  try {
    const data = await pluginConfigApi.candidates(instanceId.value, selectedPlugin.value)
    candidates.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('加载候选路径失败：' + (e?.message || e))
    candidates.value = []
  } finally {
    candidatesLoading.value = false
  }
}

watch(showCandidates, (val) => {
  if (val) loadCandidates()
})

function canEditFile(file: PluginConfigFile): boolean {
  return canEditConfigFile(file)
}

function tempDisabledReason(file: PluginConfigFile, item: PluginConfigItem): string {
  return tempConfigDisabledReason(file, item)
}

function canApplyTemp(file: PluginConfigFile, item: PluginConfigItem): boolean {
  return !tempDisabledReason(file, item)
}

function onResetDefaults(file: PluginConfigFile) {
  if (!file.items.length) return
  resetFileDefaults(file)
  ElMessage.success('已还原为默认值（请点击保存以生效）')
}

async function onApplyTemp(file: PluginConfigFile, item: PluginConfigItem) {
  if (!selectedPlugin.value || !instanceId.value || !canApplyTemp(file, item)) return
  applyingTempKey.value = `${file.fileId}:${item.key}`
  try {
    await pluginConfigApi.applyTemp({
      instanceId: instanceId.value,
      cvarName: item.key,
      cvarValue: item.value
    })
    ElMessage.success(`${item.key} 已临时设置，服务器重启后失效`)
  } catch (e: any) {
    ElMessage.error('临时设置失败：' + (e?.message || e))
  } finally {
    applyingTempKey.value = ''
  }
}

async function onSave(file: PluginConfigFile) {
  if (!selectedPlugin.value || !instanceId.value) return
  try {
    await ElMessageBox.confirm(
      `确认保存配置文件 "${file.configName}" 的配置？`,
      '确认保存',
      { type: 'warning', confirmButtonText: '保存', cancelButtonText: '取消' }
    )
    savingFileId.value = file.fileId
    await pluginConfigApi.update({
      instanceId: instanceId.value,
      pluginName: selectedPlugin.value,
      fileId: file.fileId,
      configPath: file.configPath,
      items: file.items.map(it => ({
        key: it.key,
        value: it.value,
        lineNumber: it.lineNumber
      }))
    })
    ElMessage.success(`${file.configName} 保存成功`)
    onPluginChange(selectedPlugin.value)
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    savingFileId.value = ''
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

/**
 * 恢复默认配置：调用后端立即将带 Default 注释的配置项重置并写回配置文件，
 * 与底部"还原默认值"（仅暂存表格、需再点保存）不同，本操作直接落盘。
 */
async function onRestoreDefaults() {
  if (!selectedPlugin.value || !instanceId.value) return
  const editableFiles = configFiles.value.filter(
    file => file.status !== 'ERROR' && file.status !== 'MISSING'
  )
  const count = editableFiles.flatMap(file => file.items).filter(
    it => it.defaultValue !== undefined && it.defaultValue !== ''
  ).length
  if (!count) {
    ElMessage.warning('该配置没有可恢复的默认值（配置项缺少 Default 注释）')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将把 ${count} 个配置项恢复为默认值，并立即写回各自的配置文件（其余项保持不变）。确认恢复？`,
      '恢复默认配置',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    for (const file of editableFiles) {
      const fileHasDefaults = file.items.some(
        it => it.defaultValue !== undefined && it.defaultValue !== ''
      )
      if (fileHasDefaults) {
        await pluginConfigApi.restoreDefaults(
          instanceId.value,
          selectedPlugin.value,
          file.fileId,
          file.configPath
        )
      }
    }
    ElMessage.success('已恢复默认配置')
    onPluginChange(selectedPlugin.value)
  } catch (e: any) {
    ElMessage.error('恢复默认失败：' + (e?.message || e))
  }
}

onMounted(() => {
  const fromQuery = route.query.pluginName
  if (fromQuery && typeof fromQuery === 'string') {
    selectedPlugin.value = fromQuery
  }
  loadPlugins()
})
</script>

<style scoped>
.plugin-config-page {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.empty-tip {
  padding: 32px 0;
}
.config-path {
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--platform-text-secondary);
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.config-path .label {
  font-weight: 500;
}
.config-path code {
  background: var(--platform-surface-2);
  border: 1px solid var(--platform-line);
  padding: 2px 6px;
  border-radius: 3px;
  font-family: 'Monaco', 'Consolas', monospace;
  font-size: 12px;
  color: var(--platform-cyan);
}
.config-path .synced {
  color: var(--platform-text-muted);
  font-size: 12px;
}
.config-file-groups {
  border-top: 1px solid var(--platform-line);
}
.config-file-title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  width: 100%;
  padding-right: 16px;
}
.config-file-title .file-name {
  flex-shrink: 0;
  font-family: 'Monaco', 'Consolas', monospace;
  font-size: 13px;
  font-weight: 600;
}
.config-file-title .file-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--platform-text-secondary);
  font-family: 'Monaco', 'Consolas', monospace;
  font-size: 12px;
}
.config-file-title .synced {
  margin-left: auto;
  flex-shrink: 0;
  color: var(--platform-text-muted);
  font-size: 12px;
}
.file-status-alert {
  margin: 8px 0 12px;
}
.muted {
  color: var(--platform-text-secondary);
  font-size: 13px;
}
.footer-actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
