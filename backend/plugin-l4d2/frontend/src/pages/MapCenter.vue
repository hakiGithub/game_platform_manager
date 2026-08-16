<template>
  <div class="map-center-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / MAP CENTER</span>
        <h2>地图中心</h2>
        <p>浏览与检索可用的社区地图资源</p>
      </div>
      <div class="header-actions">
        <el-button @click="loadList" :loading="loading">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <!-- 爬取状态条 -->
    <el-card class="crawl-status-card page-card" shadow="never">
      <div class="crawl-status">
        <div class="crawl-info">
          <el-icon :size="18" class="crawl-icon"><DataAnalysis /></el-icon>
          <div class="crawl-text">
            <span class="crawl-label">爬取状态：</span>
            <el-tag :type="crawlStatusType" size="small">{{ crawlStatusText }}</el-tag>
            <span v-if="crawlStatus && crawlStatus.totalMaps != null" class="crawl-detail">
              共 {{ crawlStatus.totalMaps }} 张 · 新增 {{ crawlStatus.newMaps ?? 0 }} · 更新 {{ crawlStatus.updatedMaps ?? 0 }}
              <template v-if="crawlStatus.failedMaps"> · 失败 {{ crawlStatus.failedMaps }}</template>
            </span>
            <span v-else class="crawl-detail">暂无爬取记录</span>
          </div>
        </div>
        <el-button type="primary" @click="openCrawlDialog" :disabled="isCrawling">
          <el-icon><Download /></el-icon>
          手动爬取
        </el-button>
      </div>
      <el-progress
        v-if="isCrawling && crawlStatus"
        :percentage="crawlProgress"
        :status="crawlProgressStatus"
        :stroke-width="8"
        class="crawl-progress"
      />
    </el-card>

    <!-- 筛选 -->
    <el-card class="filter-card page-card" shadow="never">
      <div class="filter-row">
        <el-input
          v-model="query.keyword"
          placeholder="搜索地图关键词"
          clearable
          class="filter-input"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select
          v-model="query.source"
          placeholder="来源"
          class="filter-select"
          @change="handleSearch"
        
        >
          <el-option label="全部来源" value="" />
          <el-option label="ORANGE" value="ORANGE" />
          <el-option label="WORKSHOP" value="WORKSHOP" />
        </el-select>
        <el-select
          v-model="query.mode"
          placeholder="游戏模式"
          class="filter-select"
          @change="handleSearch"
        
        >
          <el-option label="全部模式" value="" />
          <el-option label="合作" value="合作" />
          <el-option label="对抗" value="对抗" />
          <el-option label="生存" value="生存" />
          <el-option label="清道夫" value="清道夫" />
        </el-select>
        <el-select
          v-model="query.sort"
          placeholder="排序"
          class="filter-select-sort"
          @change="handleSearch"
        
        >
          <el-option label="更新时间倒序" value="updateDateDesc" />
          <el-option label="文件大小倒序" value="fileSizeDesc" />
          <el-option label="评分倒序" value="ratingDesc" />
          <el-option label="查看次数倒序" value="viewCountDesc" />
        </el-select>
        <el-button type="primary" @click="handleSearch">查询</el-button>
      </div>
    </el-card>

    <!-- 地图列表 -->
    <el-card class="list-card page-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="mapList"
        row-key="id"
        stripe
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="描述" :span="2">
                  {{ row.description || '暂无描述' }}
                </el-descriptions-item>
                <el-descriptions-item label="VPK 文件">{{ row.vpkFileName || '-' }}</el-descriptions-item>
                <el-descriptions-item label="地图类型">{{ row.mapType || '-' }}</el-descriptions-item>
                <el-descriptions-item label="平台">{{ row.platform || '-' }}</el-descriptions-item>
                <el-descriptions-item label="语言">{{ row.language || '-' }}</el-descriptions-item>
                <el-descriptions-item label="授权">{{ row.license || '-' }}</el-descriptions-item>
                <el-descriptions-item label="查看次数">{{ row.viewCount || 0 }}</el-descriptions-item>
                <el-descriptions-item label="来源链接" :span="2">
                  <el-link
                    v-if="row.sourceUrl"
                    type="primary"
                    :href="row.sourceUrl"
                    target="_blank"
                  >
                    {{ row.sourceUrl }}
                  </el-link>
                  <span v-else>-</span>
                </el-descriptions-item>
              </el-descriptions>

              <!-- 地图命令 -->
              <div v-if="row.mapCommands && row.mapCommands.length" class="expand-section">
                <h4 class="section-title">地图命令</h4>
                <pre class="command-block"><code>{{ row.mapCommands.join('\n') }}</code></pre>
              </div>

              <!-- 下载链接 -->
              <div v-if="row.downloadLinks && row.downloadLinks.length" class="expand-section">
                <h4 class="section-title">下载链接</h4>
                <div class="links-list">
                  <div
                    v-for="(link, idx) in row.downloadLinks"
                    :key="idx"
                    class="link-item"
                  >
                    <el-tag size="small" type="info">{{ link.channel || '默认' }}</el-tag>
                    <el-link
                      :href="link.shareUrl"
                      target="_blank"
                      type="primary"
                      class="link-url"
                    >
                      {{ link.shareUrl }}
                    </el-link>
                    <span v-if="link.accessCode" class="access-code">
                      提取码：<strong>{{ link.accessCode }}</strong>
                    </span>
                    <el-button size="small" link type="primary" @click="copyDownloadInfo(link)">
                      复制
                    </el-button>
                  </div>
                </div>
              </div>

              <!-- 截图 -->
              <div v-if="row.screenshotUrls && row.screenshotUrls.length" class="expand-section">
                <h4 class="section-title">截图（{{ row.screenshotUrls.length }}）</h4>
                <div class="screenshot-gallery">
                  <el-image
                    v-for="(url, idx) in row.screenshotUrls"
                    :key="idx"
                    :src="url"
                    :preview-src-list="row.screenshotUrls"
                    :initial-index="idx"
                    fit="cover"
                    class="screenshot-thumb"
                    lazy
                    hide-on-click-modal
                  />
                </div>
              </div>

              <el-empty
                v-if="!hasExpandDetail(row)"
                description="暂无更多详情"
                :image-size="60"
              />
            </div>
          </template>
        </el-table-column>

        <el-table-column label="缩略图" width="90">
          <template #default="{ row }">
            <el-image
              v-if="row.thumbnailUrl"
              :src="row.thumbnailUrl"
              fit="cover"
              class="thumbnail"
              lazy
            />
            <div v-else class="no-thumb">
              <el-icon :size="20"><Picture /></el-icon>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="标题" min-width="220">
          <template #default="{ row }">
            <div class="title-cell">
              <div class="title-cn">{{ row.titleCn || row.titleEn || '未命名' }}</div>
              <div v-if="row.titleEn && row.titleCn" class="title-en">{{ row.titleEn }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="文件大小" width="110">
          <template #default="{ row }">
            {{ row.fileSize || formatBytes(row.fileSizeBytes) }}
          </template>
        </el-table-column>

        <el-table-column label="游戏模式" min-width="170">
          <template #default="{ row }">
            <template v-if="row.gameModes && row.gameModes.length">
              <el-tag
                v-for="mode in row.gameModes"
                :key="mode"
                size="small"
                type="success"
                effect="plain"
                class="mode-tag"
              >
                {{ mode }}
              </el-tag>
            </template>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="章节" width="80" align="center">
          <template #default="{ row }">
            {{ row.chapterCount || 0 }}
          </template>
        </el-table-column>

        <el-table-column label="评分" width="160" align="center">
          <template #default="{ row }">
            <el-rate
              :model-value="row.starRating || 0"
              disabled
              :max="5"
              show-score
              text-color="var(--platform-amber)"
            />
          </template>
        </el-table-column>

        <el-table-column prop="author" label="作者" width="140">
          <template #default="{ row }">
            {{ row.author || '-' }}
          </template>
        </el-table-column>

        <el-table-column label="更新日期" width="120">
          <template #default="{ row }">
            {{ row.sourceUpdateDate || row.mapDate || '-' }}
          </template>
        </el-table-column>

        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag :type="getSourceTagType(row.source)" size="small" effect="dark">
              {{ row.source || '-' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 爬取对话框 -->
    <el-dialog v-model="showCrawlDialog" title="手动爬取" width="500">
      <el-form label-width="100px">
        <el-form-item label="爬取类型">
          <el-radio-group v-model="crawlType">
            <el-radio label="FULL">全量爬取</el-radio>
            <el-radio label="INCREMENTAL">增量爬取</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="isCrawling && crawlStatus" label="爬取进度">
          <div class="dialog-progress">
            <el-progress :percentage="crawlProgress" :status="crawlProgressStatus" />
            <div class="crawl-progress-detail">
              已处理 {{ crawlStatus.processedPages }} / {{ crawlStatus.totalPages }} 页 ·
              新增 {{ crawlStatus.newMaps }} · 更新 {{ crawlStatus.updatedMaps }} ·
              跳过 {{ crawlStatus.skippedMaps }} · 失败 {{ crawlStatus.failedMaps }}
            </div>
            <div v-if="crawlStatus.errorMessage" class="crawl-error">
              {{ crawlStatus.errorMessage }}
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCrawlDialog = false" :disabled="crawlSubmitting">关闭</el-button>
        <el-button
          type="primary"
          @click="handleTriggerCrawl"
          :loading="crawlSubmitting"
          :disabled="isCrawling"
        >
          开始爬取
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getMapList,
  getMapDetail,
  triggerCrawl,
  getCrawlStatus,
} from '@/api/mapCenter'
import type {
  MapCenterQuery,
  MapCenterVO,
  CrawlStatusVO,
  DownloadLink,
} from '@/api/mapCenter'

// ===== 地图列表 =====
const loading = ref(false)
const mapList = ref<MapCenterVO[]>([])
const total = ref(0)

const query = reactive<MapCenterQuery>({
  keyword: '',
  source: '',
  mode: '',
  page: 1,
  size: 20,
  sort: 'updateDateDesc',
})

// 记录已加载详情的行，避免重复请求
const detailedIds = ref<Set<string>>(new Set())

async function loadList() {
  loading.value = true
  try {
    const data = await getMapList({
      keyword: query.keyword || undefined,
      source: query.source || undefined,
      mode: query.mode || undefined,
      page: query.page,
      size: query.size,
      sort: query.sort || undefined,
    })
    mapList.value = data?.records || []
    total.value = data?.total || 0
    detailedIds.value = new Set()
  } catch (e: any) {
    ElMessage.error('加载地图列表失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadList()
}

async function handleExpandChange(row: MapCenterVO, expandedRows: MapCenterVO[]) {
  const isExpanded = expandedRows.some((r) => r.id === row.id)
  if (!isExpanded) return
  if (detailedIds.value.has(row.id)) return
  if (!row.sourceId) {
    detailedIds.value.add(row.id)
    return
  }
  try {
    const detail = await getMapDetail(row.sourceId)
    // 合并详情字段（列表数据可能不全）
    Object.assign(row, detail)
  } catch (e: any) {
    // 静默，保留列表已有数据
    console.warn('[MapCenter] load detail failed:', e?.message || e)
  } finally {
    detailedIds.value.add(row.id)
  }
}

function hasExpandDetail(row: MapCenterVO): boolean {
  return Boolean(
    row.description ||
      (row.mapCommands && row.mapCommands.length) ||
      (row.downloadLinks && row.downloadLinks.length) ||
      (row.screenshotUrls && row.screenshotUrls.length)
  )
}

// ===== 爬取状态 =====
const crawlStatus = ref<CrawlStatusVO | null>(null)
const showCrawlDialog = ref(false)
const crawlType = ref<'FULL' | 'INCREMENTAL'>('INCREMENTAL')
const crawlSubmitting = ref(false)

const ACTIVE_STATUSES = ['PENDING', 'RUNNING', 'PROCESSING', 'QUEUED']

const isCrawling = computed(() => {
  const s = crawlStatus.value?.status
  return Boolean(s && ACTIVE_STATUSES.includes(s))
})

const crawlProgress = computed(() => {
  const s = crawlStatus.value
  if (!s || !s.totalPages) return 0
  return Math.min(100, Math.round((s.processedPages / s.totalPages) * 100))
})

const crawlProgressStatus = computed<'success' | 'exception' | 'warning' | undefined>(() => {
  const s = crawlStatus.value?.status
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED') return 'exception'
  if (s === 'CANCELLED') return 'warning'
  return undefined
})

const crawlStatusText = computed(() => {
  if (!crawlStatus.value) return '未查询'
  const map: Record<string, string> = {
    PENDING: '等待中',
    QUEUED: '排队中',
    RUNNING: '爬取中',
    PROCESSING: '处理中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return map[crawlStatus.value.status] || crawlStatus.value.status
})

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

const crawlStatusType = computed<TagType>(() => {
  const s = crawlStatus.value?.status
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s && ACTIVE_STATUSES.includes(s)) return 'primary'
  if (s === 'CANCELLED') return 'warning'
  return 'info'
})

async function refreshCrawlStatus() {
  try {
    const data = await getCrawlStatus()
    crawlStatus.value = data
    if (isCrawling.value) {
      if (!crawlTimer) startCrawlPolling()
    } else {
      stopCrawlPolling()
    }
  } catch (e: any) {
    // 静默，避免轮询期间网络抖动刷屏
    console.warn('[MapCenter] refresh crawl status failed:', e?.message || e)
  }
}

function openCrawlDialog() {
  showCrawlDialog.value = true
}

async function handleTriggerCrawl() {
  crawlSubmitting.value = true
  try {
    await triggerCrawl(crawlType.value)
    ElMessage.success('爬取任务已触发')
    await refreshCrawlStatus()
    if (isCrawling.value) {
      startCrawlPolling()
    }
  } catch (e: any) {
    ElMessage.error('触发爬取失败：' + (e?.message || e))
  } finally {
    crawlSubmitting.value = false
  }
}

// ===== 轮询（遵循项目约定：start 前先清旧定时器，卸载时清理）=====
let crawlTimer: number | null = null

function stopCrawlPolling() {
  if (crawlTimer) {
    clearInterval(crawlTimer)
    crawlTimer = null
  }
}

function startCrawlPolling() {
  stopCrawlPolling()
  crawlTimer = window.setInterval(refreshCrawlStatus, 3000)
}

// ===== 工具方法 =====
function formatBytes(bytes: number): string {
  if (!bytes) return '-'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

function getSourceTagType(source: string): TagType {
  if (source === 'ORANGE') return 'warning'
  if (source === 'WORKSHOP') return 'success'
  return 'info'
}

async function copyDownloadInfo(link: DownloadLink) {
  const text = link.accessCode
    ? `${link.shareUrl}\n提取码: ${link.accessCode}`
    : link.shareUrl
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

// ===== 生命周期 =====
onMounted(async () => {
  await loadList()
  await refreshCrawlStatus()
  if (isCrawling.value) {
    startCrawlPolling()
  }
})

onBeforeUnmount(() => {
  stopCrawlPolling()
})
</script>

<style lang="scss" scoped>
.map-center-page {
  height: 100%;
  padding: 16px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;

  .header-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }
}

.crawl-status-card {
  flex-shrink: 0;

  :deep(.el-card__body) {
    padding: 16px;
  }

  .crawl-status {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
  }

  .crawl-info {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    flex: 1;
  }

  .crawl-icon {
    color: var(--platform-cyan);
    flex-shrink: 0;
  }

  .crawl-text {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    min-width: 0;
  }

  .crawl-label {
    font-size: 14px;
    color: var(--platform-text-secondary);
  }

  .crawl-detail {
    font-size: 13px;
    color: var(--platform-text-regular);
  }

  .crawl-progress {
    margin-top: 12px;
  }
}

.filter-card {
  flex-shrink: 0;

  :deep(.el-card__body) {
    padding: 16px;
  }

  .filter-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
  }

  .filter-input {
    width: 240px;
  }

  .filter-select {
    width: 140px;
  }

  .filter-select-sort {
    width: 180px;
  }
}

.list-card {
  flex: 1;
  min-height: 0;

  :deep(.el-card__body) {
    padding: 16px;
  }

  .thumbnail {
    width: 64px;
    height: 40px;
    border-radius: 4px;
    display: block;
  }

  .no-thumb {
    width: 64px;
    height: 40px;
    border-radius: 4px;
    background: var(--platform-surface-2);
    border: 1px solid var(--platform-line);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--platform-text-muted);
  }

  .title-cell {
    display: flex;
    flex-direction: column;
    gap: 2px;

    .title-cn {
      font-size: 14px;
      font-weight: 600;
      color: var(--platform-text-primary);
      line-height: 1.3;
      word-break: break-word;
    }

    .title-en {
      font-size: 12px;
      color: var(--platform-text-secondary);
      line-height: 1.2;
      word-break: break-word;
    }
  }

  .mode-tag {
    margin-right: 4px;
    margin-bottom: 4px;
  }

  .muted {
    color: var(--platform-text-muted);
  }

  .pagination-wrapper {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }
}

.expand-content {
  padding: 8px 16px 8px 48px;

  :deep(.el-descriptions) {
    margin-bottom: 8px;
  }

  .expand-section {
    margin-top: 16px;

    .section-title {
      margin: 0 0 10px 0;
      font-size: 14px;
      font-weight: 600;
      color: var(--platform-text-primary);
    }
  }

  .command-block {
    margin: 0;
    padding: 12px 14px;
    background: var(--platform-surface-2);
    border: 1px solid var(--platform-line);
    border-radius: 6px;
    overflow-x: auto;

    code {
      font-family: 'Consolas', 'Monaco', monospace;
      font-size: 13px;
      color: var(--platform-cyan);
      white-space: pre;
      line-height: 1.6;
    }
  }

  .links-list {
    display: flex;
    flex-direction: column;
    gap: 10px;

    .link-item {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      padding: 8px 12px;
      background: var(--platform-surface-1);
      border: 1px solid var(--platform-line);
      border-radius: 6px;

      .link-url {
        flex: 1;
        min-width: 200px;
        word-break: break-all;
      }

      .access-code {
        font-size: 13px;
        color: var(--platform-text-secondary);

        strong {
          color: var(--platform-amber);
          font-family: 'Consolas', 'Monaco', monospace;
        }
      }
    }
  }

  .screenshot-gallery {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    gap: 10px;

    .screenshot-thumb {
      width: 100%;
      height: 90px;
      border-radius: 6px;
      cursor: pointer;
      border: 1px solid var(--platform-line);
    }
  }
}

.dialog-progress {
  width: 100%;

  .crawl-progress-detail {
    margin-top: 8px;
    font-size: 13px;
    color: var(--platform-text-secondary);
  }

  .crawl-error {
    margin-top: 8px;
    font-size: 13px;
    color: var(--platform-red);
  }
}
</style>
