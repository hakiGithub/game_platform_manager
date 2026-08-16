<template>
  <div class="playtime-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / PLAYTIME</span>
        <h2>游玩时长</h2>
        <p>通过 Steam Web API 查询玩家 L4D2 总时长与实战时长</p>
      </div>
    </div>

    <el-card shadow="never" class="query-card">
      <template #header>
        <div class="card-header">
          <span>Steam 游玩时长查询</span>
          <span class="header-tip">通过 Steam Web API 查询玩家 L4D2 总时长与实战时长</span>
        </div>
      </template>

      <el-form :inline="true" class="query-form" @submit.prevent="query">
        <el-form-item label="SteamID" required>
          <el-input
            v-model="steamId"
            placeholder="格式：STEAM_1:0:12345"
            clearable
            style="width: 320px"
            @keyup.enter="query"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            @click="query"
          >
            查询
          </el-button>
          <el-button @click="reset">清空</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
        style="margin-top: 12px"
      />

      <el-card
        v-if="result"
        shadow="hover"
        class="result-card"
      >
        <template #header>
          <div class="card-header">
            <span>查询结果</span>
            <el-tag type="success" size="small">{{ result.source || 'Steam Web API' }}</el-tag>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="原始 SteamID">
            <code class="steam-id">{{ result.steamId || '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="SteamID64">
            <code class="steam-id">{{ result.steamId64 || '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="总时长">
            <span class="duration-text">
              {{ result.totalPlaytimeHours.toFixed(2) }} 小时
            </span>
            <span class="duration-sub">
              （约 {{ formatHours(result.totalPlaytimeHours) }}）
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="实战时长">
            <span class="duration-text">
              {{ result.realPlaytimeHours.toFixed(2) }} 小时
            </span>
            <span class="duration-sub">
              （约 {{ formatHours(result.realPlaytimeHours) }}）
            </span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="result-tip">
          <el-icon><InfoFilled /></el-icon>
          <span>
            总时长来自 Steam GetOwnedGames 接口（playtime_forever / 60）；
            实战时长来自 GetUserStatsForGame 接口（Stat.TotalPlayTime.Total / 3600）。
          </span>
        </div>
      </el-card>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { playtimeApi, type PlaytimeVO } from '@/api'

const steamId = ref('')
const loading = ref(false)
const result = ref<PlaytimeVO | null>(null)
const errorMessage = ref('')

async function query() {
  errorMessage.value = ''
  const id = steamId.value.trim()
  if (!id) {
    ElMessage.warning('请填写 SteamID')
    return
  }
  if (!/^STEAM_[0-9]:[01]:\d+$/i.test(id)) {
    errorMessage.value = 'SteamID 格式不正确，应为 STEAM_X:Y:Z 格式（例如 STEAM_1:0:12345）'
    return
  }
  loading.value = true
  result.value = null
  try {
    const data = await playtimeApi.query(id)
    result.value = data
    ElMessage.success('查询成功')
  } catch (e: any) {
    errorMessage.value = '查询失败：' + (e?.message || e)
  } finally {
    loading.value = false
  }
}

function reset() {
  steamId.value = ''
  result.value = null
  errorMessage.value = ''
}

function formatHours(hours: number): string {
  if (!hours || hours <= 0) return '0 天'
  const days = Math.floor(hours / 24)
  const remainingHours = Math.floor(hours % 24)
  if (days > 0) {
    return `${days} 天 ${remainingHours} 小时`
  }
  return `${remainingHours} 小时`
}
</script>

<style scoped>
.playtime-page {
  padding: 12px;
}

.query-card {
  border-radius: 6px;
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

.query-form {
  margin-top: 4px;
}

.result-card {
  margin-top: 16px;
  border-radius: 6px;
}

.steam-id {
  font-family: 'Consolas', 'Monaco', monospace;
  background: var(--platform-surface-1);
  border: 1px solid var(--platform-line);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  color: var(--platform-cyan);
}

.duration-text {
  font-size: 16px;
  font-weight: 600;
  color: var(--platform-cyan);
}

.duration-sub {
  margin-left: 8px;
  font-size: 12px;
  color: var(--platform-text-secondary);
}

.result-tip {
  margin-top: 12px;
  padding: 8px 12px;
  background: var(--platform-surface-1);
  border: 1px solid var(--platform-line);
  border-radius: 6px;
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  color: var(--platform-text-secondary);
  line-height: 1.6;
}

.result-tip .el-icon {
  margin-top: 2px;
  flex-shrink: 0;
}
</style>
