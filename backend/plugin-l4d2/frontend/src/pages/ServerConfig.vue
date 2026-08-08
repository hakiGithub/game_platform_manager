<template>
  <div class="server-config-page">
    <!-- 页面标题 -->
    <div class="page-header">
      <h1 class="page-title">服务器配置管理</h1>
      <div class="header-actions">
        <el-button @click="openFileDialog">
          <el-icon><Document /></el-icon>
          查看原始文件
        </el-button>
        <el-button type="warning" :loading="reloading" @click="handleReload">
          <el-icon><Refresh /></el-icon>
          重载配置
        </el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          <el-icon><Check /></el-icon>
          保存配置
        </el-button>
      </div>
    </div>

    <el-skeleton :loading="loading" :rows="12" animated>
      <template #default>
        <!-- 基础配置表单 -->
        <el-card shadow="never" class="config-card">
          <template #header>
            <div class="card-header">
              <span>基础配置</span>
              <span class="header-tip">保存后会同步到所有已存在的 tick 配置文件</span>
            </div>
          </template>
          <el-form :model="configForm" label-width="160px">
            <el-form-item label="服务器名称">
              <el-input v-model="configForm.hostname" placeholder="L4D2 Server" />
            </el-form-item>

            <el-form-item label="RCON 密码">
              <el-input
                v-model="configForm.rconPassword"
                type="password"
                show-password
                placeholder="RCON 远程管理密码"
              />
            </el-form-item>

            <el-form-item label="服务器密码">
              <el-input
                v-model="configForm.svPassword"
                type="password"
                show-password
                placeholder="留空则无密码"
              />
            </el-form-item>

            <el-form-item label="最大玩家数">
              <el-input-number v-model="configForm.maxPlayers" :min="1" :max="32" />
            </el-form-item>

            <el-form-item label="可见玩家数">
              <el-input-number v-model="configForm.visibleMaxPlayers" :min="1" :max="32" />
            </el-form-item>

            <el-form-item label="起始地图">
              <el-input v-model="configForm.mapName" placeholder="c1m1_hotel" />
            </el-form-item>

            <el-form-item label="游戏模式">
              <el-select v-model="configForm.gameMode" style="width: 100%" teleported>
                <el-option
                  v-for="mode in GAME_MODES"
                  :key="mode.value"
                  :label="mode.label"
                  :value="mode.value"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="难度">
              <el-select v-model="configForm.difficulty" style="width: 100%" teleported>
                <el-option
                  v-for="diff in DIFFICULTIES"
                  :key="diff.value"
                  :label="diff.label"
                  :value="diff.value"
                />
              </el-select>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 额外配置 KV 表 -->
        <el-card shadow="never" class="config-card">
          <template #header>
            <div class="card-header">
              <span>额外配置</span>
              <el-button type="primary" size="small" @click="addExtraRow">
                <el-icon><Plus /></el-icon>
                增加行
              </el-button>
            </div>
          </template>
          <el-table :data="extraRows" border style="width: 100%">
            <el-table-column label="键" min-width="200">
              <template #default="{ row, $index }">
                <el-input
                  v-model="row.key"
                  placeholder="如 sv_consistency"
                  @change="syncExtraFromRows"
                />
                <span v-if="duplicateKeyIndex($index)" class="dup-tip">键重复</span>
              </template>
            </el-table-column>
            <el-table-column label="值" min-width="240">
              <template #default="{ row }">
                <el-input
                  v-model="row.value"
                  placeholder="如 1"
                  @change="syncExtraFromRows"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ $index }">
                <el-button type="danger" size="small" @click="removeExtraRow($index)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="extraRows.length === 0" class="empty-tip">暂无额外配置项</div>
        </el-card>

        <!-- 自定义配置 -->
        <el-card shadow="never" class="config-card">
          <template #header>
            <div class="card-header">
              <span>自定义配置</span>
              <span class="header-tip">附加到 server.cfg 末尾（marker 之后）</span>
            </div>
          </template>
          <el-input
            v-model="configForm.customConfig"
            type="textarea"
            :rows="10"
            placeholder="在此输入自定义配置，将附加到 server.cfg 末尾（marker `// [L4D2-MANAGER-CUSTOM]` 之后）"
            style="font-family: 'Consolas', 'Monaco', monospace"
          />
        </el-card>
      </template>
    </el-skeleton>

    <!-- 原始文件查看弹窗 -->
    <el-dialog
      v-model="fileDialogVisible"
      title="查看 / 编辑原始配置文件"
      width="80%"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="file-dialog-toolbar">
        <el-select
          v-model="currentFileName"
          placeholder="选择配置文件"
          style="width: 240px"
          teleported
          @change="loadFileContent"
        >
          <el-option
            v-for="name in FILE_NAMES"
            :key="name"
            :label="name"
            :value="name"
          />
        </el-select>
        <el-button :loading="fileLoading" @click="loadFileContent">
          <el-icon><Refresh /></el-icon>
          重新加载
        </el-button>
        <el-button type="primary" :loading="fileSaving" @click="saveFileContent">
          <el-icon><Check /></el-icon>
          保存文件
        </el-button>
      </div>
      <el-input
        v-model="fileContent"
        type="textarea"
        :rows="20"
        placeholder="选择文件后展示内容"
        style="font-family: 'Consolas', 'Monaco', monospace; margin-top: 12px"
      />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  serverConfigApi,
  type ServerConfigVO,
  type ServerConfigUpdateDTO
} from '@/api'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

// ===== 常量 =====
const GAME_MODES = [
  { value: 'coop', label: '战役 (coop)' },
  { value: 'realism', label: '写实 (realism)' },
  { value: 'versus', label: '对抗 (versus)' },
  { value: 'survival', label: '生存 (survival)' },
  { value: 'scavenge', label: '拾荒 (scavenge)' }
]

const DIFFICULTIES = [
  { value: 'easy', label: '简单 (easy)' },
  { value: 'normal', label: '普通 (normal)' },
  { value: 'hard', label: '高级 (hard)' },
  { value: 'impossible', label: '专家 (impossible)' }
]

const FILE_NAMES = [
  'server.cfg',
  'server.cfg.128tick',
  'server.cfg.100tick',
  'server.cfg.60tick',
  'server.cfg.30tick'
]

// ===== 配置表单 =====
const loading = ref(false)
const saving = ref(false)
const reloading = ref(false)

const configForm = ref<ServerConfigVO>({
  hostname: '',
  rconPassword: '',
  svPassword: '',
  maxPlayers: 8,
  visibleMaxPlayers: 8,
  mapName: 'c1m1_hotel',
  gameMode: 'coop',
  difficulty: 'normal',
  extraConfig: {},
  customConfig: ''
})

// 额外配置行（KV 表的可编辑结构）
interface ExtraRow {
  key: string
  value: string
}
const extraRows = ref<ExtraRow[]>([])

function syncExtraFromVO() {
  const map = configForm.value.extraConfig || {}
  extraRows.value = Object.entries(map).map(([key, value]) => ({ key, value: String(value) }))
}

function syncExtraFromRows() {
  const map: Record<string, string> = {}
  for (const row of extraRows.value) {
    const k = row.key.trim()
    if (k) {
      map[k] = row.value
    }
  }
  configForm.value.extraConfig = map
}

function addExtraRow() {
  extraRows.value.push({ key: '', value: '' })
}

function removeExtraRow(index: number) {
  extraRows.value.splice(index, 1)
  syncExtraFromRows()
}

function duplicateKeyIndex(currentIndex: number): boolean {
  const current = extraRows.value[currentIndex]?.key.trim()
  if (!current) return false
  return extraRows.value.some(
    (row, idx) => idx !== currentIndex && row.key.trim() === current
  )
}

// ===== 加载配置 =====
async function loadConfig() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  loading.value = true
  try {
    const data = await serverConfigApi.get(instanceId.value)
    configForm.value = {
      hostname: data.hostname ?? '',
      rconPassword: data.rconPassword ?? '',
      svPassword: data.svPassword ?? '',
      maxPlayers: data.maxPlayers ?? 8,
      visibleMaxPlayers: data.visibleMaxPlayers ?? 8,
      mapName: data.mapName ?? 'c1m1_hotel',
      gameMode: data.gameMode ?? 'coop',
      difficulty: data.difficulty ?? 'normal',
      extraConfig: data.extraConfig ?? {},
      customConfig: data.customConfig ?? ''
    }
    syncExtraFromVO()
  } catch (e: any) {
    ElMessage.error('加载服务器配置失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

// ===== 保存配置 =====
async function handleSave() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  // 检查重复键
  const hasDuplicate = extraRows.value.some((_, idx) => duplicateKeyIndex(idx))
  if (hasDuplicate) {
    ElMessage.error('额外配置存在重复键，请先修正')
    return
  }
  syncExtraFromRows()
  const payload: ServerConfigUpdateDTO = {
    instanceId: instanceId.value,
    hostname: configForm.value.hostname,
    rconPassword: configForm.value.rconPassword,
    svPassword: configForm.value.svPassword,
    maxPlayers: configForm.value.maxPlayers,
    visibleMaxPlayers: configForm.value.visibleMaxPlayers,
    mapName: configForm.value.mapName,
    gameMode: configForm.value.gameMode,
    difficulty: configForm.value.difficulty,
    extraConfig: configForm.value.extraConfig,
    customConfig: configForm.value.customConfig
  }
  saving.value = true
  try {
    await serverConfigApi.update(payload)
    ElMessage.success('已保存并同步到所有已存在的 tick 配置文件')
  } catch (e: any) {
    ElMessage.error('保存配置失败：' + (e?.message || e))
  } finally {
    saving.value = false
  }
}

// ===== 重载配置 =====
async function handleReload() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  try {
    await ElMessageBox.confirm(
      '将通过 RCON 发送 exec server.cfg 命令重载配置，是否继续？',
      '重载配置确认',
      { type: 'warning', confirmButtonText: '重载', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  reloading.value = true
  try {
    await serverConfigApi.reload(instanceId.value)
    ElMessage.success('已发送 exec server.cfg 命令')
  } catch (e: any) {
    ElMessage.error('重载配置失败：' + (e?.message || e))
  } finally {
    reloading.value = false
  }
}

// ===== 原始文件查看弹窗 =====
const fileDialogVisible = ref(false)
const currentFileName = ref<string>('server.cfg')
const fileContent = ref<string>('')
const fileLoading = ref(false)
const fileSaving = ref(false)

async function openFileDialog() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  fileDialogVisible.value = true
  currentFileName.value = 'server.cfg'
  await loadFileContent()
}

async function loadFileContent() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  fileLoading.value = true
  try {
    const content = await serverConfigApi.getFileContent(instanceId.value, currentFileName.value)
    fileContent.value = content ?? ''
  } catch (e: any) {
    ElMessage.error('获取文件内容失败：' + (e?.message || e))
    fileContent.value = ''
  } finally {
    fileLoading.value = false
  }
}

async function saveFileContent() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  fileSaving.value = true
  try {
    await serverConfigApi.updateFileContent(
      instanceId.value,
      currentFileName.value,
      fileContent.value
    )
    ElMessage.success('文件已保存')
  } catch (e: any) {
    ElMessage.error('保存文件失败：' + (e?.message || e))
  } finally {
    fileSaving.value = false
  }
}

onMounted(() => {
  loadConfig()
})
</script>

<style scoped>
.server-config-page {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.config-card {
  border-radius: 4px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.header-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: normal;
}

.empty-tip {
  padding: 16px;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.dup-tip {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
}

.file-dialog-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
