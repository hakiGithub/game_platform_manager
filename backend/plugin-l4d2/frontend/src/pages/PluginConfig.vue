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
          teleported
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
      </div>
    </div>

    <el-card v-loading="loading" class="page-card" shadow="never">
      <div v-if="!selectedPlugin" class="empty-tip">
        <el-empty description="请先选择插件" />
      </div>

      <div v-else>
        <div v-if="config?.configPath" class="config-path">
          <span class="label">配置文件路径:</span>
          <code>{{ config.configPath }}</code>
          <span v-if="config.lastSyncedAt" class="synced">
            最后同步: {{ formatTime(config.lastSyncedAt) }}
          </span>
        </div>

        <el-table :data="tableItems" stripe>
          <el-table-column prop="key" label="配置项 Key" min-width="180" />
          <el-table-column label="Value" min-width="220">
            <template #default="{ row }">
              <el-input v-model="row.value" size="small" />
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
        </el-table>

        <div class="footer-actions">
          <el-button @click="onResetDefaults" :disabled="!tableItems.length">
            还原默认值
          </el-button>
          <el-button type="primary" @click="onSave" :loading="saving" :disabled="!tableItems.length">
            保存
          </el-button>
        </div>
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

interface ConfigItem {
  key: string
  value: string
  defaultValue?: string
  min?: number
  max?: number
  description?: string
  lineNumber: number
}

interface PluginConfigData {
  pluginName: string
  configName?: string
  configPath?: string
  items: ConfigItem[]
  rawContent?: string
  lastSyncedAt?: string
}

const route = useRoute()
const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const plugins = ref<PluginListVO[]>([])
const pluginsLoading = ref(false)
const selectedPlugin = ref('')
const loading = ref(false)
const saving = ref(false)

const config = ref<PluginConfigData | null>(null)
const tableItems = ref<ConfigItem[]>([])

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
    const data = await pluginManageApi.list(instanceId.value)
    plugins.value = Array.isArray(data) ? data : []
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
  if (!name || !instanceId.value) {
    config.value = null
    tableItems.value = []
    return
  }
  loading.value = true
  try {
    const data = await pluginConfigApi.get(instanceId.value, name)
    config.value = data
    tableItems.value = (data.items || []).map(it => ({ ...it }))
  } catch (e: any) {
    ElMessage.error('加载配置失败：' + (e?.message || e))
    config.value = null
    tableItems.value = []
  } finally {
    loading.value = false
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

function onResetDefaults() {
  if (!tableItems.value.length) return
  tableItems.value.forEach(it => {
    if (it.defaultValue !== undefined) {
      it.value = it.defaultValue
    }
  })
  ElMessage.success('已还原为默认值（请点击保存以生效）')
}

async function onSave() {
  if (!selectedPlugin.value || !instanceId.value) return
  try {
    await ElMessageBox.confirm(
      `确认保存插件 "${selectedPlugin.value}" 的配置？`,
      '确认保存',
      { type: 'warning', confirmButtonText: '保存', cancelButtonText: '取消' }
    )
    saving.value = true
    await pluginConfigApi.update({
      instanceId: instanceId.value,
      pluginName: selectedPlugin.value,
      items: tableItems.value.map(it => ({
        key: it.key,
        value: it.value,
        lineNumber: it.lineNumber
      }))
    })
    ElMessage.success('保存成功')
    onPluginChange(selectedPlugin.value)
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    saving.value = false
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
