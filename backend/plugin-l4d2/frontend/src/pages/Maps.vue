<template>
  <div class="maps-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>地图管理</span>
          <div class="header-actions">
            <el-button type="primary" @click="showUploadDialog = true">上传地图</el-button>
            <el-button :disabled="!selectedRows.length" @click="handleTrimBatch">
              批量裁剪（{{ selectedRows.length }}）
            </el-button>
            <el-button @click="handleHotReload">热重载</el-button>
            <el-button @click="loadList">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="mapList"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column prop="vpkName" label="VPK 文件名" min-width="200" />
        <el-table-column prop="title" label="标题" min-width="150" />
        <el-table-column label="章节数" width="100">
          <template #default="{ row }">
            {{ row.chapters?.length || 0 }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="handleTrim(row)">裁剪</el-button>
            <el-button size="small" @click="handleDetail(row)">详情</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

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
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mapApi } from '@/api'
import type { MapListVO, VpkTrimResultVO, MissionInfoVO } from '@/api'
import { usePluginStore } from '@/stores/plugin'
import ChunkUploader from '@/components/ChunkUploader.vue'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const mapList = ref<MapListVO[]>([])
const selectedRows = ref<MapListVO[]>([])
const loading = ref(false)
const showUploadDialog = ref(false)
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

onMounted(loadList)
</script>

<style scoped>
.maps-page {
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
}
</style>
