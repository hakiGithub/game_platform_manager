<template>
  <div class="monitor-page">
    <!-- 页头 -->
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / PERFORMANCE</span>
        <h2>性能监控</h2>
        <p>实时资源占用与历史趋势分析</p>
      </div>
      <div class="header-actions">
        <span class="hero-state">
          <span class="status-dot" :class="config.collectEnabled ? 'running' : 'stopped'"></span>
          {{ config.collectEnabled ? '采集中' : '已停用' }}
        </span>
      </div>
    </div>

    <!-- 顶部：采集开关 + 配置展示 -->
    <el-card shadow="never" class="config-card">
      <div class="config-row">
        <div class="config-left">
          <span class="config-title">系统监控采集</span>
          <el-switch
            v-model="config.collectEnabled"
            :loading="switchLoading"
            @change="handleSwitchChange"
          />
          <el-tag
            :type="config.collectEnabled ? 'success' : 'info'"
            size="small"
            style="margin-left: 8px"
          >
            {{ config.collectEnabled ? '采集中' : '已停用' }}
          </el-tag>
        </div>
        <div class="config-right">
          <span class="config-item">
            采集间隔：<strong>{{ config.collectIntervalMs }} ms</strong>
          </span>
          <el-divider direction="vertical" />
          <span class="config-item">
            保留时长：<strong>{{ formatRetentionMs(config.retentionMs) }}</strong>
          </span>
          <el-divider direction="vertical" />
          <span class="config-item">
            最大点数：<strong>{{ config.maxPoints }}</strong>
          </span>
          <el-divider direction="vertical" />
          <span class="config-item">
            降采样目标：<strong>{{ config.downsampleTo }}</strong>
          </span>
          <el-divider direction="vertical" />
          <span class="config-item">
            历史持久化：
            <el-tag :type="config.historyEnabled ? 'success' : 'info'" size="small">
              {{ config.historyEnabled ? '启用' : '停用' }}
            </el-tag>
          </span>
        </div>
      </div>
    </el-card>

    <!-- 实时状态卡片 -->
    <el-row :gutter="12" class="realtime-cards">
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card shadow="hover" class="stat-card cpu">
          <div class="stat-header">
            <span class="stat-label">CPU 使用率</span>
            <el-icon :size="18"><Cpu /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value" :class="cpuStatus">{{ cpuPercent.toFixed(1) }}%</div>
            <el-progress
              :percentage="cpuPercent"
              :stroke-width="10"
              :show-text="false"
              :status="cpuStatus"
            />
          </div>
          <div class="stat-footer">
            <span class="stat-extra">最高核心 {{ formatNumber(currentStatus?.cpuMaxCore) }}%</span>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card shadow="hover" class="stat-card memory">
          <div class="stat-header">
            <span class="stat-label">内存使用</span>
            <el-icon :size="18"><Coin /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value" :class="memStatus">{{ memPercent.toFixed(1) }}%</div>
            <el-progress
              :percentage="memPercent"
              :stroke-width="10"
              :show-text="false"
              :status="memStatus"
            />
          </div>
          <div class="stat-footer">
            <span class="stat-extra">{{ formatGB(currentStatus?.memUsed) }} / {{ formatGB(currentStatus?.memTotal) }}</span>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card shadow="hover" class="stat-card swap">
          <div class="stat-header">
            <span class="stat-label">交换内存</span>
            <el-icon :size="18"><Files /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value">{{ formatGB(currentStatus?.swapUsed) }}</div>
          </div>
          <div class="stat-footer">
            <span class="stat-extra">Swap 已用</span>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card shadow="hover" class="stat-card disk">
          <div class="stat-header">
            <span class="stat-label">磁盘使用</span>
            <el-icon :size="18"><FolderOpened /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value" :class="diskStatus">{{ diskPercent.toFixed(1) }}%</div>
            <el-progress
              :percentage="diskPercent"
              :stroke-width="10"
              :show-text="false"
              :status="diskStatus"
            />
          </div>
          <div class="stat-footer">
            <span class="stat-extra">{{ formatGB(currentStatus?.diskUsed) }} / {{ formatGB(currentStatus?.diskTotal) }}</span>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card shadow="hover" class="stat-card net-up">
          <div class="stat-header">
            <span class="stat-label">网络上行</span>
            <el-icon :size="18"><Top /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value net">{{ formatKBps(currentStatus?.netUpSpeed) }}</div>
          </div>
          <div class="stat-footer">
            <span class="stat-extra">TX 速率</span>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card shadow="hover" class="stat-card net-down">
          <div class="stat-header">
            <span class="stat-label">网络下行</span>
            <el-icon :size="18"><Bottom /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value net">{{ formatKBps(currentStatus?.netDownSpeed) }}</div>
          </div>
          <div class="stat-footer">
            <span class="stat-extra">RX 速率</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 历史趋势图 -->
    <el-card shadow="never" class="history-card">
      <template #header>
        <div class="card-header">
          <span>历史趋势</span>
          <div class="header-actions">
            <el-date-picker
              v-model="historyRange"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 360px"
              teleported
            />
            <el-button
              type="primary"
              :loading="historyLoading"
              @click="loadHistory"
            >
              查询
            </el-button>
            <el-button @click="resetHistoryRange">最近 1 小时</el-button>
          </div>
        </div>
      </template>
      <div v-loading="historyLoading" class="chart-wrap">
        <div v-show="!historyLoading && historyData.length > 0" ref="historyChartRef" class="chart"></div>
        <el-empty
          v-if="!historyLoading && historyData.length === 0"
          :image-size="120"
          :description="emptyDescription"
        >
          <template v-if="!config.collectEnabled" #default>
            <el-button type="primary" size="small" :loading="switchLoading" @click="handleSwitchChange(true)">
              开启系统监控采集
            </el-button>
          </template>
        </el-empty>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  monitorApi,
  type MonitorConfigVO,
  type MonitorStatusVO,
  type SystemMetricVO
} from '@/api'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

// ===== 配置 =====
const config = ref<MonitorConfigVO>({
  historyEnabled: true,
  collectIntervalMs: 1000,
  retentionMs: 3 * 24 * 3600 * 1000,
  maxPoints: 2000,
  downsampleTo: 720,
  collectEnabled: false
})
const switchLoading = ref(false)

async function loadConfig() {
  try {
    const data = await monitorApi.getConfig()
    if (data) {
      config.value = {
        historyEnabled: Boolean(data.historyEnabled),
        collectIntervalMs: data.collectIntervalMs,
        retentionMs: data.retentionMs,
        maxPoints: data.maxPoints,
        downsampleTo: data.downsampleTo,
        collectEnabled: Boolean(data.collectEnabled)
      }
    }
  } catch (e: any) {
    // 配置加载失败不阻塞主流程
    console.warn('加载监控配置失败：', e?.message || e)
  }
}

async function handleSwitchChange(val: boolean | string | number) {
  switchLoading.value = true
  try {
    await monitorApi.setConfig({ enable: Boolean(val) })
    ElMessage.success(val ? '采集已开启' : '采集已停用')
    await loadConfig()
  } catch (e: any) {
    config.value.collectEnabled = !val
    ElMessage.error('更新开关失败：' + (e?.message || e))
  } finally {
    switchLoading.value = false
  }
}

// ===== 实时状态 =====
const currentStatus = ref<MonitorStatusVO | null>(null)
let statusTimer: ReturnType<typeof setInterval> | null = null

const cpuPercent = computed(() => round(currentStatus.value?.cpuPercent, 1, 0, 100))
const cpuStatus = computed<'success' | 'warning' | 'exception' | ''>(() => {
  const v = cpuPercent.value
  if (v >= 90) return 'exception'
  if (v >= 70) return 'warning'
  return ''
})
const memPercent = computed(() => {
  const used = currentStatus.value?.memUsed
  const total = currentStatus.value?.memTotal
  if (!total || total <= 0 || used == null) return 0
  return round((used / total) * 100, 1, 0, 100)
})
const memStatus = computed<'success' | 'warning' | 'exception' | ''>(() => {
  const v = memPercent.value
  if (v >= 90) return 'exception'
  if (v >= 70) return 'warning'
  return ''
})
const diskPercent = computed(() => {
  const used = currentStatus.value?.diskUsed
  const total = currentStatus.value?.diskTotal
  if (!total || total <= 0 || used == null) return 0
  return round((used / total) * 100, 1, 0, 100)
})
const diskStatus = computed<'success' | 'warning' | 'exception' | ''>(() => {
  const v = diskPercent.value
  if (v >= 90) return 'exception'
  if (v >= 70) return 'warning'
  return ''
})

async function refreshStatus() {
  if (!instanceId.value) return
  try {
    const data = await monitorApi.getStatus(instanceId.value)
    currentStatus.value = data
  } catch (e: any) {
    // 静默失败，避免轮询时频繁弹错误
    console.warn('刷新监控状态失败：', e?.message || e)
  }
}

// ===== 历史趋势 =====
const historyRange = ref<[string, string] | null>(null)
const historyLoading = ref(false)
const historyData = ref<SystemMetricVO[]>([])
const historyChartRef = ref<HTMLElement | null>(null)
let historyChart: echarts.ECharts | null = null

const emptyDescription = computed(() => {
  if (!config.value.collectEnabled) {
    return '系统监控采集当前已停用，开启后将自动记录并展示历史趋势'
  }
  return '所选时间范围内暂无监控数据，请稍后查询或调整时间范围'
})

function resetHistoryRange() {
  const end = new Date()
  const start = new Date(end.getTime() - 60 * 60 * 1000)
  historyRange.value = [formatLocalDateTime(start), formatLocalDateTime(end)]
}

function formatLocalDateTime(date: Date): string {
  const pad = (n: number) => (n < 10 ? '0' + n : String(n))
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

async function loadHistory() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  if (!historyRange.value) {
    ElMessage.warning('请选择时间范围')
    return
  }
  const [startTime, endTime] = historyRange.value
  historyLoading.value = true
  try {
    const data = await monitorApi.getSystemHistory({
      instanceId: instanceId.value,
      startTime,
      endTime
    })
    historyData.value = Array.isArray(data) ? data : []
    await nextTick()
    renderHistoryChart()
  } catch (e: any) {
    ElMessage.error('加载历史数据失败：' + (e?.message || e))
  } finally {
    historyLoading.value = false
  }
}

function renderHistoryChart() {
  if (!historyChartRef.value) return
  if (!historyChart) {
    historyChart = echarts.init(historyChartRef.value)
  }
  const times = historyData.value.map(d => new Date(d.timestamp).toLocaleString('zh-CN'))
  const cpuPercentArr = historyData.value.map(d => Number(d.cpuPercent ?? 0))
  const cpuMaxCoreArr = historyData.value.map(d => Number(d.cpuMaxCore ?? 0))
  const memUsedArr = historyData.value.map(d => Number(d.memUsed ?? 0))
  const netUpArr = historyData.value.map(d => Number(d.netUpSpeed ?? 0))
  const netDownArr = historyData.value.map(d => Number(d.netDownSpeed ?? 0))
  const diskUsedArr = historyData.value.map(d => Number(d.diskUsed ?? 0))

  historyChart.setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#142735',
      borderColor: '#263847',
      textStyle: { color: '#d9e5ed' }
    },
    legend: {
      data: ['CPU 总使用率(%)', 'CPU 最高核心(%)', '内存(GB)', '网络上行(KB/s)', '网络下行(KB/s)', '磁盘(GB)'],
      textStyle: { color: '#8d9cac' }
    },
    grid: { left: 60, right: 80, bottom: 60, top: 40 },
    xAxis: {
      type: 'category',
      data: times,
      axisLabel: { rotate: 30, color: '#8d9cac' },
      axisLine: { lineStyle: { color: '#263847' } }
    },
    yAxis: {
      type: 'value',
      name: '数值',
      nameTextStyle: { color: '#8d9cac' },
      axisLabel: { color: '#8d9cac' },
      splitLine: { lineStyle: { color: '#263847' } }
    },
    series: [
      {
        name: 'CPU 总使用率(%)',
        type: 'line',
        smooth: true,
        data: cpuPercentArr,
        itemStyle: { color: '#27b5f3' }
      },
      {
        name: 'CPU 最高核心(%)',
        type: 'line',
        smooth: true,
        data: cpuMaxCoreArr,
        itemStyle: { color: '#52cf82' }
      },
      {
        name: '内存(GB)',
        type: 'line',
        smooth: true,
        data: memUsedArr,
        itemStyle: { color: '#f2b84b' }
      },
      {
        name: '网络上行(KB/s)',
        type: 'line',
        smooth: true,
        data: netUpArr,
        itemStyle: { color: '#f0646a' }
      },
      {
        name: '网络下行(KB/s)',
        type: 'line',
        smooth: true,
        data: netDownArr,
        itemStyle: { color: '#8d9cac' }
      },
      {
        name: '磁盘(GB)',
        type: 'line',
        smooth: true,
        data: diskUsedArr,
        itemStyle: { color: '#c397f5' }
      }
    ]
  })
}

// ===== 工具函数 =====
function round(value: number | null | undefined, digits = 1, min = 0, max = 100): number {
  if (value == null || isNaN(value)) return min
  const factor = Math.pow(10, digits)
  const v = Math.round(value * factor) / factor
  if (v < min) return min
  if (v > max) return max
  return v
}

function formatNumber(value: number | null | undefined): string {
  if (value == null) return '0'
  return Number(value).toFixed(1)
}

function formatGB(value: number | null | undefined): string {
  if (value == null) return '0.00 GB'
  return Number(value).toFixed(2) + ' GB'
}

function formatKBps(value: number | null | undefined): string {
  if (value == null) return '0.00 KB/s'
  return Number(value).toFixed(2) + ' KB/s'
}

function formatRetentionMs(ms: number): string {
  if (!ms || ms <= 0) return '-'
  const days = ms / (24 * 3600 * 1000)
  if (days >= 1) return `${days.toFixed(days % 1 === 0 ? 0 : 1)} 天`
  const hours = ms / (3600 * 1000)
  if (hours >= 1) return `${hours.toFixed(0)} 小时`
  return `${(ms / 60000).toFixed(0)} 分钟`
}

// ===== 生命周期 =====
function handleResize() {
  historyChart?.resize()
}

onMounted(async () => {
  await loadConfig()
  resetHistoryRange()
  await Promise.all([refreshStatus(), loadHistory()])
  // 1 秒轮询实时状态
  statusTimer = setInterval(refreshStatus, 1000)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  if (statusTimer) {
    clearInterval(statusTimer)
    statusTimer = null
  }
  window.removeEventListener('resize', handleResize)
  historyChart?.dispose()
  historyChart = null
})
</script>

<style scoped>
.monitor-page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.config-card,
.history-card {
  border-radius: 6px;
}

.config-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}

.config-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.config-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.config-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  flex-wrap: wrap;
}

.config-item strong {
  color: var(--el-text-color-primary);
  margin-left: 4px;
  font-weight: 600;
}

.realtime-cards {
  margin-bottom: 0;
}

.realtime-cards .el-col {
  margin-bottom: 12px;
}

.stat-card {
  border-radius: 6px;
  min-height: 152px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 16px;
  }
}

.stat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.stat-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  font-weight: 500;
}

.stat-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 10px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  line-height: 1.2;

  &.warning {
    color: var(--platform-amber);
  }

  &.exception {
    color: var(--platform-red);
  }

  &.net {
    font-size: 22px;
  }
}

.stat-footer {
  margin-top: 10px;
  min-height: 18px;
}

.stat-extra {
  font-size: 12px;
  color: var(--el-text-color-regular);
  font-weight: 500;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.chart-wrap {
  position: relative;
  min-height: 360px;
}

.chart {
  width: 100%;
  height: 360px;
}

:deep(.el-empty__description) {
  max-width: 420px;
  line-height: 1.6;
}
</style>
