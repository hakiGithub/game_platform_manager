<template>
  <div class="logs-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / SERVER LOGS</span>
        <h2>服务器日志</h2>
        <p>实时日志流浏览与关键字检索</p>
      </div>
      <div class="header-actions">
        <span class="hero-state">
          <span class="status-dot" :class="connected ? 'running' : (paused ? 'deploying' : 'stopped')"></span>
          {{ connected ? '已连接' : (paused ? '已暂停' : '未连接') }}
        </span>
      </div>
    </div>

    <el-row :gutter="12">
      <el-col :span="6">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>日志文件</span>
              <el-button size="small" @click="loadFileList" :loading="loading">刷新</el-button>
            </div>
          </template>
          <el-table
            :data="files"
            v-loading="loading"
            @row-click="onSelectFile"
            highlight-current-row
            size="small"
            height="calc(100vh - 220px)"
          >
            <el-table-column prop="name" label="文件名" />
            <el-table-column label="大小" width="80">
              <template #default="{ row }">{{ formatSize(row.size) }}</template>
            </el-table-column>
            <el-table-column label="修改时间" width="150">
              <template #default="{ row }">{{ formatTime(row.lastModified) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="18">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>{{ currentFile || '请选择日志文件' }}</span>
              <div class="header-actions">
                <el-tag :type="connected ? 'success' : 'info'" size="small" style="margin-right: 8px">
                  {{ connected ? '已连接' : (paused ? '已暂停' : '未连接') }}
                </el-tag>
                <el-input v-model="searchText" placeholder="搜索..." size="small" style="width: 200px; margin-right: 8px" clearable />
                <el-button-group>
                  <el-button size="small" @click="togglePause" :type="paused ? 'danger' : 'default'">
                    {{ paused ? '继续' : '暂停' }}
                  </el-button>
                  <el-button size="small" @click="clear">清空</el-button>
                </el-button-group>
              </div>
            </div>
          </template>
          <LogViewer
            :logs="logs"
            :connected="connected"
            :paused="paused"
            :search-text="searchText"
            style="height: calc(100vh - 280px)"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useLogStream } from '@/composables/useLogStream'
import LogViewer from '@/components/LogViewer.vue'
import { logsApi } from '@/api'

interface LogFileInfo {
  name: string
  path: string
  size: number
  lastModified: number
  isErrorLog: boolean
}

const files = ref<LogFileInfo[]>([])
const loading = ref(false)
const searchText = ref('')

const { logs, connected, paused, currentFile, instanceId, start, togglePause, clear } = useLogStream()

async function loadFileList() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  loading.value = true
  try {
    const data = await logsApi.listFiles(instanceId.value)
    files.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('加载日志列表失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function onSelectFile(row: LogFileInfo) {
  if (currentFile.value === row.name) return
  clear()
  start(row.name)
}

function formatSize(b: number): string {
  if (!b) return '-'
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  return (b / 1024 / 1024).toFixed(1) + ' MB'
}

function formatTime(t: number): string {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(loadFileList)
</script>

<style scoped>
.logs-page {
  padding: 12px;
}
.logs-page :deep(.el-card) {
  border-radius: 6px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  align-items: center;
}
</style>
