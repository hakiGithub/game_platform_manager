<template>
  <div class="player-stats-page">
    <!-- 页头 -->
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / PLAYER STATS</span>
        <h2>玩家统计</h2>
        <p>玩家趋势、活跃度与个人游玩记录分析</p>
      </div>
      <div class="header-actions">
        <span class="hero-state">
          <span class="status-dot" :class="config.enabled ? 'running' : 'stopped'"></span>
          {{ config.enabled ? '采集中' : '已停用' }}
        </span>
      </div>
    </div>

    <!-- 顶部：采集开关 + 配置展示 -->
    <el-card shadow="never" class="config-card">
      <div class="config-row">
        <div class="config-left">
          <span class="config-title">玩家统计采集</span>
          <el-switch
            v-model="config.enabled"
            :loading="switchLoading"
            @change="handleSwitchChange"
          />
          <el-tag
            :type="config.enabled ? 'success' : 'info'"
            size="small"
            style="margin-left: 8px"
          >
            {{ config.enabled ? '采集中' : '已停用' }}
          </el-tag>
        </div>
        <div class="config-right">
          <span class="config-item">
            采集间隔：<strong>{{ config.intervalMinutes }} 分钟</strong>
          </span>
          <el-divider direction="vertical" />
          <span class="config-item">
            保留天数：<strong>{{ config.retentionDays }} 天</strong>
          </span>
          <el-divider direction="vertical" />
          <span class="config-item">
            最近采集：
            <strong>{{ formatLastSnapshot(config.lastSnapshot) }}</strong>
          </span>
        </div>
      </div>
      <div v-if="config.lastSnapshot" class="snapshot-detail">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="服务器在线">
            <el-tag
              :type="config.lastSnapshot.serverOnline ? 'success' : 'danger'"
              size="small"
            >
              {{ config.lastSnapshot.serverOnline ? '在线' : '离线' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="采集结果">
            <el-tag
              :type="config.lastSnapshot.collectOk ? 'success' : 'warning'"
              size="small"
            >
              {{ config.lastSnapshot.collectOk ? '成功' : '失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="玩家数">
            {{ config.lastSnapshot.playerCount }} / {{ config.lastSnapshot.maxPlayers }}
          </el-descriptions-item>
          <el-descriptions-item label="地图">
            {{ config.lastSnapshot.map || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="难度">
            {{ config.lastSnapshot.difficulty || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="游戏模式">
            {{ config.lastSnapshot.gameMode || '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="config.lastSnapshot.errorMessage" label="错误信息" :span="2">
            <span class="error-text">{{ config.lastSnapshot.errorMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-card>

    <!-- Tab 切换 -->
    <el-card shadow="never" class="tabs-card">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <!-- Tab 1: 趋势图 -->
        <el-tab-pane label="趋势图" name="trend">
          <div class="filter-bar">
            <el-date-picker
              v-model="trendRange"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="X"
              format="YYYY-MM-DD HH:mm"
              style="width: 360px"
              teleported
            />
            <el-radio-group v-model="trendBucket" style="margin-left: 12px">
              <el-radio-button value="hour">按小时</el-radio-button>
              <el-radio-button value="day">按天</el-radio-button>
            </el-radio-group>
            <el-button
              type="primary"
              style="margin-left: 12px"
              :loading="trendLoading"
              @click="loadTrend"
            >
              查询
            </el-button>
            <el-button @click="resetTrendRange">最近 7 天</el-button>
          </div>
          <div v-loading="trendLoading" class="chart-wrap">
            <div ref="trendChartRef" class="chart"></div>
            <div v-if="!trendLoading && trendData.length === 0" class="empty-tip">
              暂无趋势数据
            </div>
          </div>
        </el-tab-pane>

        <!-- Tab 2: 玩家列表 -->
        <el-tab-pane label="玩家列表" name="players">
          <div class="filter-bar">
            <el-input
              v-model="playerKeyword"
              placeholder="按 steamId 或昵称模糊搜索"
              clearable
              style="width: 280px"
              @keyup.enter="searchPlayers"
            />
            <el-button
              type="primary"
              style="margin-left: 12px"
              :loading="playersLoading"
              @click="searchPlayers"
            >
              搜索
            </el-button>
            <el-button @click="resetPlayerSearch">重置</el-button>
            <span class="filter-tip">
              共 {{ playersData.length }} 条结果
            </span>
          </div>

          <el-table
            v-loading="playersLoading"
            :data="pagedPlayers"
            stripe
            empty-text="暂无玩家数据"
          >
            <el-table-column label="排名" width="80" prop="rank" />
            <el-table-column label="SteamID" min-width="180" prop="steamId" show-overflow-tooltip />
            <el-table-column label="昵称" min-width="160" prop="name" show-overflow-tooltip />
            <el-table-column label="归属地" width="120" prop="location" show-overflow-tooltip />
            <el-table-column label="IP" width="160" prop="ip" show-overflow-tooltip />
            <el-table-column label="最后在线" width="160">
              <template #default="{ row }">
                {{ formatRelativeTime(row.lastSeen) }}
              </template>
            </el-table-column>
            <el-table-column label="预估时长" width="140">
              <template #default="{ row }">
                {{ formatMinutes(row.estimatedMinutes) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link @click="showPlayerDetail(row)">
                  查看详情
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="playersPage"
            :page-size="playersPageSize"
            :total="playersData.length"
            layout="total, prev, pager, next, jumper"
            style="margin-top: 12px; justify-content: flex-end"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 玩家详情弹窗 -->
    <el-dialog
      v-model="detailDialogVisible"
      :title="`玩家详情 - ${currentPlayer?.name || currentPlayer?.steamId || ''}`"
      width="80%"
      destroy-on-close
    >
      <el-tabs v-model="detailTab" @tab-change="handleDetailTabChange">
        <el-tab-pane label="按日统计" name="days">
          <el-table
            v-loading="daysLoading"
            :data="daysData"
            stripe
            empty-text="暂无按日统计数据"
            max-height="480"
          >
            <el-table-column label="日期" prop="date" width="140" />
            <el-table-column label="在线时长">
              <template #default="{ row }">
                {{ formatMinutes(row.onlineMinutes) }}
              </template>
            </el-table-column>
            <el-table-column label="采样数" prop="samples" width="120" />
            <el-table-column label="首次出现">
              <template #default="{ row }">
                {{ formatUnixTime(row.firstSeen) }}
              </template>
            </el-table-column>
            <el-table-column label="最后出现">
              <template #default="{ row }">
                {{ formatUnixTime(row.lastSeen) }}
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="别名记录" name="aliases">
          <el-table
            v-loading="aliasesLoading"
            :data="aliasesData"
            stripe
            empty-text="暂无别名记录"
            max-height="480"
          >
            <el-table-column label="昵称" prop="name" min-width="200" show-overflow-tooltip />
            <el-table-column label="采样数" prop="samples" width="120" />
            <el-table-column label="预估时长">
              <template #default="{ row }">
                {{ formatMinutes(row.estimatedMinutes) }}
              </template>
            </el-table-column>
            <el-table-column label="首次出现">
              <template #default="{ row }">
                {{ formatUnixTime(row.firstSeen) }}
              </template>
            </el-table-column>
            <el-table-column label="最后出现">
              <template #default="{ row }">
                {{ formatUnixTime(row.lastSeen) }}
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  playerStatsApi,
  type PlayerStatsConfigVO,
  type PlayerStatsTrendVO,
  type PlayerStatsPlayerVO,
  type PlayerStatsDayVO,
  type PlayerStatsAliasVO
} from '@/api'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

// ===== 配置区 =====
const config = ref<PlayerStatsConfigVO>({
  enabled: false,
  intervalMinutes: 10,
  retentionDays: 30,
  lastSnapshot: null
})
const switchLoading = ref(false)

async function loadConfig() {
  try {
    const data = await playerStatsApi.getConfig()
    config.value = {
      enabled: Boolean(data?.enabled),
      intervalMinutes: data?.intervalMinutes ?? 10,
      retentionDays: data?.retentionDays ?? 30,
      lastSnapshot: data?.lastSnapshot ?? null
    }
  } catch (e: any) {
    ElMessage.error('加载玩家统计配置失败：' + (e?.message || e))
  }
}

async function handleSwitchChange(val: boolean | string | number) {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  switchLoading.value = true
  try {
    await playerStatsApi.setConfig({ enable: Boolean(val) })
    ElMessage.success(val ? '采集已开启' : '采集已停用')
    await loadConfig()
  } catch (e: any) {
    // 失败时回滚
    config.value.enabled = !val
    ElMessage.error('更新开关失败：' + (e?.message || e))
  } finally {
    switchLoading.value = false
  }
}

// ===== Tab 切换 =====
const activeTab = ref<'trend' | 'players'>('trend')

function handleTabChange(name: string | number) {
  if (name === 'players' && playersData.value.length === 0 && instanceId.value) {
    searchPlayers()
  } else if (name === 'trend' && trendData.value.length === 0) {
    loadTrend()
  }
}

// ===== 趋势图 =====
const trendRange = ref<[string, string] | null>(null)
const trendBucket = ref<'hour' | 'day'>('hour')
const trendLoading = ref(false)
const trendData = ref<PlayerStatsTrendVO[]>([])
const trendChartRef = ref<HTMLElement | null>(null)
let trendChart: echarts.ECharts | null = null

function resetTrendRange() {
  const end = Math.floor(Date.now() / 1000)
  const start = end - 7 * 24 * 3600
  trendRange.value = [String(start), String(end)]
}

async function loadTrend() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  if (!trendRange.value) {
    ElMessage.warning('请选择时间范围')
    return
  }
  const [startStr, endStr] = trendRange.value
  const start = Number(startStr)
  const end = Number(endStr)
  trendLoading.value = true
  try {
    const data = await playerStatsApi.getHourly({
      instanceId: instanceId.value,
      start,
      end,
      bucket: trendBucket.value
    })
    trendData.value = Array.isArray(data) ? data : []
    await nextTick()
    renderTrendChart()
  } catch (e: any) {
    ElMessage.error('加载趋势数据失败：' + (e?.message || e))
  } finally {
    trendLoading.value = false
  }
}

function renderTrendChart() {
  if (!trendChartRef.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value)
  }
  const times = trendData.value.map(d =>
    formatUnixTime(d.timestamp, trendBucket.value === 'day' ? 'MM-DD' : 'MM-DD HH:mm')
  )
  const avgPlayers = trendData.value.map(d => d.avgPlayers ?? 0)
  const peakPlayers = trendData.value.map(d => d.peakPlayers ?? 0)
  const uniquePlayers = trendData.value.map(d => Number(d.uniquePlayers ?? 0))
  const offlineSamples = trendData.value.map(d => Number(d.offlineSamples ?? 0))
  const sampleCount = trendData.value.map(d => Number(d.sampleCount ?? 0))

  trendChart.setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#142735',
      borderColor: '#263847',
      textStyle: { color: '#d9e5ed' }
    },
    legend: {
      data: ['平均玩家', '峰值玩家', '独立玩家', '离线采样', '总采样'],
      textStyle: { color: '#8d9cac' }
    },
    grid: { left: 60, right: 70, bottom: 60, top: 40 },
    xAxis: {
      type: 'category',
      data: times,
      axisLabel: { rotate: 30, color: '#8d9cac' },
      axisLine: { lineStyle: { color: '#263847' } }
    },
    yAxis: [
      {
        type: 'value',
        name: '玩家数',
        position: 'left',
        nameTextStyle: { color: '#8d9cac' },
        axisLabel: { color: '#8d9cac' },
        splitLine: { lineStyle: { color: '#263847' } }
      },
      {
        type: 'value',
        name: '采样数',
        position: 'right',
        nameTextStyle: { color: '#8d9cac' },
        axisLabel: { color: '#8d9cac' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '平均玩家',
        type: 'line',
        smooth: true,
        data: avgPlayers,
        itemStyle: { color: '#27b5f3' }
      },
      {
        name: '峰值玩家',
        type: 'line',
        smooth: true,
        data: peakPlayers,
        itemStyle: { color: '#52cf82' }
      },
      {
        name: '独立玩家',
        type: 'line',
        smooth: true,
        data: uniquePlayers,
        itemStyle: { color: '#f2b84b' }
      },
      {
        name: '离线采样',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: offlineSamples,
        itemStyle: { color: '#f0646a' }
      },
      {
        name: '总采样',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: sampleCount,
        itemStyle: { color: '#8d9cac' }
      }
    ]
  })
}

// ===== 玩家列表 =====
const playerKeyword = ref('')
const playersLoading = ref(false)
const playersData = ref<PlayerStatsPlayerVO[]>([])
const playersPage = ref(1)
const playersPageSize = 20

const pagedPlayers = computed(() => {
  const start = (playersPage.value - 1) * playersPageSize
  return playersData.value.slice(start, start + playersPageSize)
})

async function searchPlayers() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  playersLoading.value = true
  playersPage.value = 1
  try {
    const params: { instanceId: number; keyword?: string } = {
      instanceId: instanceId.value
    }
    if (playerKeyword.value.trim()) {
      params.keyword = playerKeyword.value.trim()
    }
    const data = await playerStatsApi.searchPlayers(params)
    playersData.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('搜索玩家失败：' + (e?.message || e))
  } finally {
    playersLoading.value = false
  }
}

function resetPlayerSearch() {
  playerKeyword.value = ''
  searchPlayers()
}

// ===== 玩家详情 =====
const detailDialogVisible = ref(false)
const currentPlayer = ref<PlayerStatsPlayerVO | null>(null)
const detailTab = ref<'days' | 'aliases'>('days')
const daysLoading = ref(false)
const daysData = ref<PlayerStatsDayVO[]>([])
const aliasesLoading = ref(false)
const aliasesData = ref<PlayerStatsAliasVO[]>([])

async function showPlayerDetail(row: PlayerStatsPlayerVO) {
  currentPlayer.value = row
  detailDialogVisible.value = true
  detailTab.value = 'days'
  daysData.value = []
  aliasesData.value = []
  await loadPlayerDays()
}

async function handleDetailTabChange(name: string | number) {
  if (name === 'days' && daysData.value.length === 0) {
    await loadPlayerDays()
  } else if (name === 'aliases' && aliasesData.value.length === 0) {
    await loadPlayerAliases()
  }
}

async function loadPlayerDays() {
  if (!instanceId.value || !currentPlayer.value?.steamId) return
  daysLoading.value = true
  try {
    const data = await playerStatsApi.getPlayerDays(currentPlayer.value.steamId, {
      instanceId: instanceId.value
    })
    daysData.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('加载按日统计失败：' + (e?.message || e))
  } finally {
    daysLoading.value = false
  }
}

async function loadPlayerAliases() {
  if (!instanceId.value || !currentPlayer.value?.steamId) return
  aliasesLoading.value = true
  try {
    const data = await playerStatsApi.getPlayerAliases(currentPlayer.value.steamId, {
      instanceId: instanceId.value
    })
    aliasesData.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('加载别名记录失败：' + (e?.message || e))
  } finally {
    aliasesLoading.value = false
  }
}

// ===== 工具函数 =====
function formatUnixTime(ts: number | null | undefined, fmt?: string): string {
  if (!ts) return '-'
  const date = new Date(ts * 1000)
  if (fmt === 'MM-DD') {
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  }
  if (fmt === 'MM-DD HH:mm') {
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  return date.toLocaleString('zh-CN')
}

function pad(n: number): string {
  return n < 10 ? '0' + n : String(n)
}

function formatRelativeTime(ts: number | null | undefined): string {
  if (!ts) return '-'
  const now = Math.floor(Date.now() / 1000)
  const diff = now - ts
  if (diff < 0) return '刚刚'
  if (diff < 60) return `${diff} 秒前`
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  if (diff < 30 * 86400) return `${Math.floor(diff / 86400)} 天前`
  return formatUnixTime(ts)
}

function formatMinutes(minutes: number | null | undefined): string {
  if (!minutes || minutes <= 0) return '-'
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h > 0) return `${h} 小时 ${m} 分钟`
  return `${m} 分钟`
}

function formatLastSnapshot(snap: PlayerStatsConfigVO['lastSnapshot']): string {
  if (!snap || !snap.timestamp) return '暂无'
  return formatUnixTime(snap.timestamp)
}

// ===== 生命周期 =====
function handleResize() {
  trendChart?.resize()
}

onMounted(async () => {
  await loadConfig()
  resetTrendRange()
  await loadTrend()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  trendChart = null
})
</script>

<style scoped>
.player-stats-page {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.config-card,
.tabs-card {
  border-radius: 6px;
}

.config-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.config-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.config-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.config-right {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  flex-wrap: wrap;
}

.config-item strong {
  color: var(--el-text-color-primary);
  margin-left: 4px;
}

.snapshot-detail {
  margin-top: 12px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.filter-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.chart-wrap {
  position: relative;
  min-height: 380px;
}

.chart {
  width: 100%;
  height: 380px;
}

.empty-tip {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.error-text {
  color: var(--platform-red);
  word-break: break-all;
}
</style>
