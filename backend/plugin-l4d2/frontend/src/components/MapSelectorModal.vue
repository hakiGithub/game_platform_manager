<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    title="切换地图"
    width="880px"
  >
    <div class="map-selector" v-loading="loading">
      <!-- 工具栏：官图开关 + 搜索 + 刷新 -->
      <div class="selector-toolbar">
        <el-switch v-model="showOfficial" active-text="显示官方地图" />
        <el-input
          v-model="searchKeyword"
          placeholder="搜索战役 / 章节 / 地图代码 / VPK..."
          clearable
          class="selector-search"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button :loading="loading" @click="loadData">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>

      <el-alert
        v-if="!instanceId"
        title="请先选择实例"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <!-- 战役手风琴：官方 + 三方分组，章节卡片点击即换图 -->
      <el-collapse v-else v-model="expandedCampaigns" class="campaign-list">
        <el-collapse-item
          v-for="campaign in filteredCampaigns"
          :key="campaign.key"
          :name="campaign.key"
        >
          <template #title>
            <div class="campaign-title">
              <span class="campaign-name">{{ campaign.title }}</span>
              <el-tag size="small" :type="campaign.isCustom ? 'warning' : 'success'">
                {{ campaign.isCustom ? '三方' : '官方' }}
              </el-tag>
              <span class="campaign-meta">{{ campaign.chapters.length }} 章节</span>
              <span v-if="campaign.vpkName" class="campaign-meta vpk">{{ campaign.vpkName }}</span>
            </div>
          </template>
          <div class="chapter-grid">
            <div
              v-for="chapter in campaign.chapters"
              :key="campaign.key + '-' + chapter.code"
              class="chapter-card"
              :class="{ changing: changingCode === chapter.code }"
              @click="handleChangeMap(chapter)"
            >
              <div class="chapter-name">{{ chapter.title || chapter.code }}</div>
              <div class="chapter-code">{{ chapter.code }}</div>
              <div class="chapter-modes">
                <el-tag
                  v-for="mode in (chapter.modes || []).slice(0, 3)"
                  :key="mode"
                  size="small"
                  type="info"
                >
                  {{ mode }}
                </el-tag>
              </div>
              <div v-if="changingCode === chapter.code" class="chapter-changing">
                <el-icon class="is-loading"><Loading /></el-icon>
              </div>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>

      <el-empty
        v-if="!loading && instanceId && !filteredCampaigns.length"
        description="没有可切换的地图"
      />
    </div>

    <template #footer>
      <span class="footer-tip">点击章节卡片即向服务器发送 changelevel 切图指令，当前对局将中断</span>
      <el-button @click="$emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { mapApi, rconApi } from '@/api'
import type { MapListVO } from '@/api'
import { officialChapters, officialChapterCodes } from '@/data/officialChapters'

interface SelectorChapter {
  code: string
  title?: string
  modes?: string[]
}

interface SelectorCampaign {
  key: string
  title: string
  isCustom: boolean
  vpkName?: string
  chapters: SelectorChapter[]
}

const props = defineProps<{
  modelValue: boolean
  instanceId?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 换图指令发送成功，携带章节码 */
  success: [mapCode: string]
}>()

const loading = ref(false)
const showOfficial = ref(true)
const searchKeyword = ref('')
const campaigns = ref<SelectorCampaign[]>([])
const expandedCampaigns = ref<string[]>([])
/** 正在换图的章节码；非空时禁用其他卡片（防连点） */
const changingCode = ref('')

/**
 * 合并官方章节目录与服务器 addons 扫描结果：
 * 官方目录始终可用（官方图内置于游戏本体）；
 * 服务器战役章节码命中官图目录则视为官方去重跳过，否则归为三方。
 */
function mergeMaps(serverMaps: MapListVO[]): SelectorCampaign[] {
  const result: SelectorCampaign[] = officialChapters.map((campaign, index) => ({
    key: `official-${index}`,
    title: campaign.title,
    isCustom: false,
    chapters: campaign.chapters.map(ch => ({ ...ch }))
  }))

  for (const server of serverMaps || []) {
    const chapters = (server.chapters || [])
      .filter(ch => Boolean(ch.code))
      .map(ch => ({ code: ch.code, title: ch.title, modes: ch.modes }))
    if (!chapters.length) continue
    if (chapters.some(ch => officialChapterCodes.has(ch.code))) continue
    result.push({
      key: `custom-${server.vpkName || result.length}`,
      title: server.title || server.vpkName || '未知战役',
      isCustom: true,
      vpkName: server.vpkName,
      chapters
    })
  }
  return result
}

async function loadData() {
  if (!props.instanceId) return
  loading.value = true
  try {
    const serverMaps = await mapApi.list(props.instanceId)
    campaigns.value = mergeMaps(Array.isArray(serverMaps) ? serverMaps : [])
    expandedCampaigns.value = campaigns.value.length ? [campaigns.value[0].key] : []
  } catch (e: any) {
    ElMessage.error('加载地图列表失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

const filteredCampaigns = computed(() => {
  let result = campaigns.value
  if (!showOfficial.value) {
    result = result.filter(c => c.isCustom)
  }
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (keyword) {
    result = result
      .map(campaign => {
        const campaignHit =
          campaign.title.toLowerCase().includes(keyword) ||
          (campaign.vpkName || '').toLowerCase().includes(keyword)
        const chapters = campaignHit
          ? campaign.chapters
          : campaign.chapters.filter(
              ch =>
                ch.code.toLowerCase().includes(keyword) ||
                (ch.title || '').toLowerCase().includes(keyword)
            )
        return { ...campaign, chapters }
      })
      .filter(c => c.chapters.length > 0)
  }
  return result
})

async function handleChangeMap(chapter: SelectorChapter) {
  if (!props.instanceId) {
    ElMessage.warning('请先选择实例')
    return
  }
  if (changingCode.value) return
  changingCode.value = chapter.code
  try {
    await rconApi.changeMap(props.instanceId, chapter.code)
    ElMessage.success(`地图切换指令已发送：${chapter.code}`)
    emit('success', chapter.code)
    emit('update:modelValue', false)
  } catch (e: any) {
    ElMessage.error('切换地图失败：' + (e?.message || e))
  } finally {
    changingCode.value = ''
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      changingCode.value = ''
      searchKeyword.value = ''
      loadData()
    }
  }
)
</script>

<style lang="scss" scoped>
.map-selector {
  min-height: 200px;

  .selector-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 14px;

    .selector-search {
      flex: 1;
    }
  }

  .campaign-list {
    max-height: 480px;
    overflow-y: auto;

    .campaign-title {
      display: flex;
      align-items: center;
      gap: 8px;

      .campaign-name {
        font-weight: 600;
      }

      .campaign-meta {
        font-size: 12px;
        color: var(--platform-text-secondary);

        &.vpk {
          font-family: 'Consolas', 'Monaco', monospace;
        }
      }
    }

    .chapter-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
      gap: 10px;
      padding: 4px 2px 8px;
    }

    .chapter-card {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: 6px;
      padding: 12px;
      border: 1px solid var(--platform-line);
      border-radius: 6px;
      background: var(--platform-surface-2);
      cursor: pointer;
      transition: border-color 0.16s, transform 0.16s;

      &:hover {
        border-color: rgba(39, 181, 243, 0.55);
        transform: translateY(-2px);
      }

      &.changing {
        opacity: 0.7;
        pointer-events: none;
        border-color: var(--platform-cyan);
      }

      .chapter-name {
        font-size: 13px;
        font-weight: 600;
        color: var(--platform-text-primary);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .chapter-code {
        font-family: 'Consolas', 'Monaco', monospace;
        font-size: 12px;
        color: var(--platform-cyan);
      }

      .chapter-modes {
        display: flex;
        gap: 4px;
        flex-wrap: wrap;
      }

      .chapter-changing {
        position: absolute;
        inset: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 22px;
        color: var(--platform-cyan);
        background: rgba(0, 0, 0, 0.35);
        border-radius: 6px;
      }
    }
  }
}

.footer-tip {
  margin-right: 12px;
  font-size: 12px;
  color: var(--platform-text-secondary);
}
</style>
