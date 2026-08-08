<template>
  <div class="preset-page">
    <el-card v-loading="loading">
      <template #header>
        <div class="card-header">
          <span>预设场景</span>
          <div class="header-actions">
            <el-tag v-if="instanceName" type="info">当前实例: {{ instanceName }}</el-tag>
            <el-button @click="loadList" :loading="loading">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <div v-if="!instanceId" class="empty-tip">
        <el-empty description="请先选择实例" />
      </div>

      <div v-else-if="!presets.length && !loading" class="empty-tip">
        <el-empty description="暂无预设" />
      </div>

      <el-row :gutter="16">
        <el-col
          v-for="preset in presets"
          :key="preset.id"
          :xs="24"
          :sm="12"
          :md="8"
          :lg="6"
        >
          <el-card class="preset-card" shadow="hover">
            <div class="preset-name">{{ preset.name }}</div>
            <div class="preset-desc">{{ preset.description || '无描述' }}</div>
            <div class="preset-tags">
              <el-tag v-if="preset.gameMode" size="small">{{ preset.gameMode }}</el-tag>
              <el-tag v-if="preset.maxPlayers !== undefined" size="small" type="info">
                {{ preset.maxPlayers }} 人
              </el-tag>
            </div>
            <div class="preset-actions">
              <el-button size="small" @click="openDetail(preset)">查看详情</el-button>
              <el-button type="danger" size="small" @click="onApply(preset)">应用预设</el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-drawer
      v-model="showDetail"
      :title="detail?.name || '预设详情'"
      size="45%"
      direction="rtl"
    >
      <div v-loading="detailLoading">
        <div v-if="detail" class="detail-content">
          <div class="detail-meta">
            <p v-if="detail.description"><strong>描述:</strong> {{ detail.description }}</p>
            <p v-if="detail.gameMode"><strong>游戏模式:</strong> {{ detail.gameMode }}</p>
            <p v-if="detail.maxPlayers !== undefined">
              <strong>最大玩家:</strong> {{ detail.maxPlayers }}
            </p>
            <p v-if="detail.platform"><strong>平台:</strong> {{ detail.platform }}</p>
          </div>

          <el-divider content-position="left">启用插件 ({{ detail.enabledPlugins.length }})</el-divider>
          <div v-if="detail.enabledPlugins.length" class="plugin-list">
            <el-tag
              v-for="name in detail.enabledPlugins"
              :key="'en-' + name"
              type="success"
              size="small"
              class="plugin-tag"
            >
              {{ name }}
            </el-tag>
          </div>
          <div v-else class="muted">无</div>

          <el-divider content-position="left">禁用插件 ({{ detail.disabledPlugins.length }})</el-divider>
          <div v-if="detail.disabledPlugins.length" class="plugin-list">
            <el-tag
              v-for="name in detail.disabledPlugins"
              :key="'dis-' + name"
              type="info"
              size="small"
              class="plugin-tag"
            >
              {{ name }}
            </el-tag>
          </div>
          <div v-else class="muted">无</div>

          <el-divider content-position="left">配置覆盖 ({{ detail.configOverrides.length }})</el-divider>
          <div v-if="detail.configOverrides.length">
            <el-collapse>
              <el-collapse-item
                v-for="(ov, idx) in detail.configOverrides"
                :key="idx"
                :title="ov.file"
                :name="idx"
              >
                <el-table :data="Object.entries(ov.items).map(([k, v]) => ({ key: k, value: v }))" size="small">
                  <el-table-column prop="key" label="Key" min-width="180" />
                  <el-table-column prop="value" label="Value" min-width="180" />
                </el-table>
              </el-collapse-item>
            </el-collapse>
          </div>
          <div v-else class="muted">无</div>

          <div class="detail-actions">
            <el-button
              type="danger"
              :loading="applying === detail.id"
              @click="onApply(detail)"
            >
              应用此预设
            </el-button>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { presetApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'

interface PresetSummary {
  id: string
  name: string
  description?: string
  gameMode?: string
  maxPlayers?: number
}

interface PresetDetail extends PresetSummary {
  platform?: string
  enabledPlugins: string[]
  disabledPlugins: string[]
  configOverrides: Array<{ file: string; items: Record<string, string> }>
}

const store = usePluginStore()
const router = useRouter()
const instanceId = computed(() => store.instanceInfo?.instanceId)
const instanceName = computed(() => store.instanceInfo?.instanceName)

const loading = ref(false)
const presets = ref<PresetSummary[]>([])

const showDetail = ref(false)
const detailLoading = ref(false)
const detail = ref<PresetDetail | null>(null)
const applying = ref('')

async function loadList() {
  loading.value = true
  try {
    const data = await presetApi.list()
    presets.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('加载预设列表失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function openDetail(preset: PresetSummary) {
  showDetail.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await presetApi.detail(preset.id)
  } catch (e: any) {
    ElMessage.error('加载详情失败：' + (e?.message || e))
  } finally {
    detailLoading.value = false
  }
}

async function onApply(preset: PresetSummary | PresetDetail) {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认应用预设 "${preset.name}" 到当前实例？\n\n此操作会改变当前所有插件的启用状态并覆盖配置，可能导致服务器重启或部分插件失效。`,
      '高危操作确认',
      {
        type: 'warning',
        confirmButtonText: '确认应用',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (e) {
    return
  }

  const loadingInstance = ElLoading.service({
    fullscreen: true,
    lock: true,
    text: '正在应用预设，请稍候...'
  })
  applying.value = preset.id
  try {
    await presetApi.apply(preset.id, instanceId.value)
    ElMessage.success('预设应用成功')
    showDetail.value = false
    router.push('/plugins')
  } catch (e: any) {
    ElMessage.error('应用预设失败：' + (e?.message || e))
  } finally {
    applying.value = ''
    loadingInstance.close()
  }
}

onMounted(loadList)
</script>

<style scoped>
.preset-page {
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
  align-items: center;
}
.empty-tip {
  padding: 32px 0;
}
.preset-card {
  margin-bottom: 16px;
}
.preset-name {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
}
.preset-desc {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  min-height: 40px;
  margin-bottom: 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.preset-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.preset-actions {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.detail-content {
  padding: 0 8px;
}
.detail-meta p {
  margin: 6px 0;
  font-size: 14px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.plugin-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.plugin-tag {
  margin: 0;
}
.detail-actions {
  margin-top: 24px;
  display: flex;
  justify-content: center;
}
</style>
