<template>
  <div class="maps-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / MAP LIBRARY</span>
        <h2>地图管理</h2>
        <p>管理服务器地图 VPK 文件，支持 VPK / ZIP / RAR / 7Z 上传（自动解包提取 VPK）、批量裁剪与热重载</p>
      </div>
      <div class="header-actions">
        <el-button type="success" @click="showMapSelector = true">切换地图</el-button>
        <el-button type="primary" @click="showUploadDialog = true">上传地图</el-button>
        <el-button :disabled="!selectedRows.length" @click="handleTrimBatch">
          批量裁剪（{{ selectedRows.length }}）
        </el-button>
        <el-button @click="handleHotReload">热重载</el-button>
        <el-button :loading="recognizing" @click="handleRecognize">识别地图</el-button>
        <el-button @click="loadList">刷新</el-button>
      </div>
    </div>

    <el-card class="page-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="mapList"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <template v-if="row.recognitionStatus === 'OK' && row.chapters?.length">
                <h4 class="expand-title">章节（{{ row.chapters.length }}）</h4>
                <div class="chapter-list">
                  <div v-for="ch in row.chapters" :key="ch.code" class="chapter-item">
                    <el-tag size="small" type="info">{{ ch.code }}</el-tag>
                    <span class="chapter-name">{{ ch.title || ch.code }}</span>
                    <el-tag
                      v-for="mode in (ch.modes || [])"
                      :key="mode"
                      size="small"
                      type="success"
                      effect="plain"
                    >
                      {{ mode }}
                    </el-tag>
                    <el-button
                      size="small"
                      type="primary"
                      link
                      :disabled="!isInstanceRunning"
                      @click="launchMap(ch.code)"
                    >
                      开图
                    </el-button>
                  </div>
                </div>
              </template>
              <div v-if="(row.launchCommands?.length || 0) > 0" class="launch-commands">
                <h4 class="expand-title">开图命令</h4>
                <div v-for="cmd in row.launchCommands" :key="cmd" class="command-line">
                  <code>{{ cmd }}</code>
                </div>
              </div>
              <el-alert
                v-if="row.recognitionStatus === 'INVALID'"
                type="info"
                :closable="false"
                title="该文件不是有效的 L4D2 地图 VPK（无 mission 信息）"
              />
              <el-alert
                v-else-if="row.recognitionStatus === 'FAILED'"
                type="warning"
                :closable="false"
                :title="'识别失败：' + (row.recognitionError || '未知原因') + '，可用「识别地图」重试'"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column type="selection" width="50" />
        <el-table-column prop="vpkName" label="VPK 文件名" min-width="200" />
        <el-table-column prop="title" label="标题" min-width="150" />
        <el-table-column label="章节数" width="100">
          <template #default="{ row }">
            {{ row.chapters?.length || 0 }}
          </template>
        </el-table-column>
        <el-table-column label="识别" width="90">
          <template #default="{ row }">
            <el-tag :type="recognitionTagType(row.recognitionStatus)" size="small">
              {{ recognitionText(row.recognitionStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="showMapSelector = true">换图</el-button>
            <el-button size="small" @click="handleTrim(row)">裁剪</el-button>
            <el-button size="small" @click="handleDetail(row)">详情</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 切换地图选择器 -->
    <MapSelectorModal v-model="showMapSelector" :instance-id="instanceId" />

    <!-- 上传对话框 -->
    <el-dialog v-model="showUploadDialog" title="上传地图" width="500">
      <ChunkUploader
        v-if="showUploadDialog && instanceId"
        :instance-id="instanceId"
        @success="handleUploadSuccess"
        @error="handleUploadError"
      />
    </el-dialog>

    <!-- 裁剪结果对话框 -->
    <el-dialog v-model="showTrimResult" title="裁剪结果" width="500">
      <el-descriptions v-if="trimResult" :column="1" border>
        <el-descriptions-item label="文件名">{{ trimResult.fileName }}</el-descriptions-item>
        <el-descriptions-item label="原大小">{{ formatBytes(trimResult.originalSize) }}</el-descriptions-item>
        <el-descriptions-item label="裁剪后">{{ formatBytes(trimResult.trimmedSize) }}</el-descriptions-item>
        <el-descriptions-item label="节省">
          <el-tag type="success">{{ formatBytes(trimResult.savedBytes) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="总条目">{{ trimResult.totalEntries }}</el-descriptions-item>
        <el-descriptions-item label="裁剪条目">{{ trimResult.trimmedEntries }}</el-descriptions-item>
        <el-descriptions-item v-if="trimResult.backupCreated" label="备份">
          {{ trimResult.backupFileName }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- Mission 详情对话框 -->
    <el-dialog v-model="showMissionDetail" title="地图详情" width="600">
      <div v-if="missionInfo">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="VPK 名">{{ missionInfo.vpkName }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ missionInfo.title || '未知' }}</el-descriptions-item>
        </el-descriptions>
        <h4 style="margin-top: 16px;">章节列表</h4>
        <el-table :data="missionInfo.chapters || []" border>
          <el-table-column prop="code" label="代码" />
          <el-table-column prop="title" label="标题" />
          <el-table-column label="模式">
            <template #default="{ row }">
              <el-tag
                v-for="mode in (row.modes || [])"
                :key="mode"
                size="small"
                style="margin-right: 4px;"
              >
                {{ mode }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mapApi, rconApi, instanceApi, mainTaskApi } from '@/api'
import type { MapListVO, VpkTrimResultVO, MissionInfoVO } from '@/api'
import { usePluginStore } from '@/stores/plugin'
import ChunkUploader from '@/components/ChunkUploader.vue'
import MapSelectorModal from '@/components/MapSelectorModal.vue'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)
const instanceRunning = ref(false)
const isInstanceRunning = computed(() => instanceRunning.value)
const recognizing = ref(false)
let recognizeTimer: number | null = null

/** 服务器运行中才允许开图（换图会中断当前对局） */
async function refreshInstanceRunning() {
  if (!instanceId.value) return
  try {
    const status = await instanceApi.getStatus(instanceId.value)
    instanceRunning.value = status?.runStatus === 1
  } catch {
    instanceRunning.value = false
  }
}

function recognitionText(status?: string): string {
  switch (status) {
    case 'OK': return '已识别'
    case 'FAILED': return '失败'
    case 'INVALID': return '无效'
    default: return '未识别'
  }
}

function recognitionTagType(status?: string): 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'OK': return 'success'
    case 'FAILED': return 'danger'
    case 'INVALID': return 'info'
    default: return 'warning'
  }
}

/** 批量识别：提交 map-recognize 任务并轮询到终态后刷新列表 */
async function handleRecognize() {
  if (!instanceId.value || recognizing.value) return
  recognizing.value = true
  try {
    const taskId = await mapApi.recognize(instanceId.value, true)
    ElMessage.success('识别任务已提交')
    recognizeTimer = window.setInterval(async () => {
      try {
        const task = await mainTaskApi.detail(taskId)
        if (task && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status)) {
          stopRecognizePolling()
          recognizing.value = false
          if (task.status === 'COMPLETED') {
            ElMessage.success(task.resultSummary || '识别完成')
          } else {
            ElMessage.warning('识别任务未完成: ' + (task.errorMessage || task.status))
          }
          await loadList()
        }
      } catch {
        // 轮询抖动忽略，下一轮继续
      }
    }, 3000)
  } catch (e: any) {
    recognizing.value = false
    ElMessage.error('提交识别任务失败：' + (e?.message || e))
  }
}

function stopRecognizePolling() {
  if (recognizeTimer) {
    clearInterval(recognizeTimer)
    recognizeTimer = null
  }
}

/** 开图：RCON 换图到指定章节码（实例运行中可用） */
async function launchMap(mapCode: string) {
  if (!instanceId.value) return
  try {
    await ElMessageBox.confirm(
      `确定切换到地图 ${mapCode}？当前对局将中断。`,
      '开图确认',
      { type: 'warning' }
    )
    await rconApi.changeMap(instanceId.value, mapCode)
    ElMessage.success('开图命令已发送')
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('开图失败：' + (e?.message || e))
    }
  }
}

const mapList = ref<MapListVO[]>([])
const selectedRows = ref<MapListVO[]>([])
const loading = ref(false)
const showUploadDialog = ref(false)
const showMapSelector = ref(false)
const showTrimResult = ref(false)
const showMissionDetail = ref(false)
const trimResult = ref<VpkTrimResultVO | null>(null)
const missionInfo = ref<MissionInfoVO | null>(null)

async function loadList() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  loading.value = true
  try {
    const data = await mapApi.list(instanceId.value)
    mapList.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function handleSelectionChange(rows: MapListVO[]) {
  selectedRows.value = rows
}

async function handleTrim(row: MapListVO) {
  if (!instanceId.value || !row.vpkName) return
  try {
    await ElMessageBox.confirm(
      `确定裁剪 ${row.vpkName}？将自动备份原文件。`,
      '裁剪确认',
      { type: 'warning' }
    )
    const result = await mapApi.trim(instanceId.value, row.vpkName)
    trimResult.value = result
    showTrimResult.value = true
    ElMessage.success(`裁剪成功，节省 ${formatBytes(result.savedBytes)}`)
    await loadList()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('裁剪失败：' + (e?.message || e))
    }
  }
}

async function handleTrimBatch() {
  if (!instanceId.value || !selectedRows.value.length) return
  const names = selectedRows.value
    .map(r => r.vpkName)
    .filter((n): n is string => Boolean(n))
  try {
    await ElMessageBox.confirm(
      `确定批量裁剪 ${names.length} 个地图？`,
      '批量裁剪确认',
      { type: 'warning' }
    )
    loading.value = true
    const results = await mapApi.trimBatch({ instanceId: instanceId.value, mapNames: names })
    const totalSaved = results.reduce((sum, r) => sum + r.savedBytes, 0)
    ElMessage.success(`批量裁剪完成，共节省 ${formatBytes(totalSaved)}`)
    await loadList()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('批量裁剪失败：' + (e?.message || e))
    }
  } finally {
    loading.value = false
  }
}

async function handleDetail(row: MapListVO) {
  if (!instanceId.value || !row.vpkName) return
  try {
    missionInfo.value = await mapApi.mission(instanceId.value, row.vpkName)
    showMissionDetail.value = true
  } catch (e: any) {
    ElMessage.error('加载详情失败：' + (e?.message || e))
  }
}

async function handleDelete(row: MapListVO) {
  if (!instanceId.value || !row.vpkName) return
  try {
    await ElMessageBox.confirm(
      `确定删除 ${row.vpkName}？此操作不可恢复。`,
      '删除确认',
      { type: 'warning' }
    )
    await mapApi.delete(instanceId.value, row.vpkName)
    ElMessage.success('删除成功')
    await loadList()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败：' + (e?.message || e))
    }
  }
}

async function handleHotReload() {
  if (!instanceId.value) return
  try {
    await ElMessageBox.confirm(
      '确定执行地图热重载？将向服务器发送 RCON 命令重新加载地图。',
      '热重载确认',
      { type: 'warning' }
    )
    await mapApi.hotReload(instanceId.value)
    ElMessage.success('热重载命令已发送')
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('热重载失败：' + (e?.message || e))
    }
  }
}

function handleUploadSuccess() {
  showUploadDialog.value = false
  loadList()
}

function handleUploadError() {
  // 错误已在组件内提示
}

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

onMounted(() => {
  loadList()
  refreshInstanceRunning()
})

onBeforeUnmount(stopRecognizePolling)
</script>

<style scoped>
.maps-page {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.expand-content {
  padding: 8px 16px 8px 48px;
}

.expand-title {
  margin: 0 0 8px 0;
  font-size: 13px;
  font-weight: 600;
}

.chapter-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}

.chapter-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.chapter-name {
  min-width: 120px;
}

.launch-commands .command-line {
  margin-bottom: 4px;
}

.launch-commands code {
  background: var(--el-fill-color-light);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}
</style>
