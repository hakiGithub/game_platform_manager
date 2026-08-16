<template>
  <div class="version-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / VERSION INFO</span>
        <h2>版本信息</h2>
        <p>插件构建版本、提交与运行环境详情</p>
      </div>
      <div class="header-actions">
        <el-button @click="copyFullInfo">
          <el-icon><CopyDocument /></el-icon>
          复制完整版本信息
        </el-button>
        <el-button type="primary" :loading="loading" @click="loadVersion">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-skeleton :loading="loading" :rows="8" animated>
      <template #default>
        <el-card shadow="never" class="page-card info-card">
          <el-descriptions v-if="info" :column="2" border>
            <el-descriptions-item label="插件版本">
              <el-tag type="success" size="small">{{ info.version || '-' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="插件 ID">
              <code class="mono-text">{{ info.pluginId || '-' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="插件描述" :span="2">
              {{ info.pluginDescription || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Git Commit">
              <code class="mono-text">{{ shortCommit }}</code>
              <el-button
                v-if="info.commit"
                link
                size="small"
                style="margin-left: 8px"
                @click="copyText(info.commit)"
              >
                <el-icon><CopyDocument /></el-icon>
                复制
              </el-button>
            </el-descriptions-item>
            <el-descriptions-item label="构建时间">
              {{ info.buildTime || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="JDK 版本">
              {{ info.jdkVersion || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="PF4J 版本">
              {{ info.pf4jVersion || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Spring Boot 版本" :span="2">
              {{ info.springBootVersion || '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </template>
    </el-skeleton>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { versionApi, type BuildInfoVO } from '@/api'

const loading = ref(false)
const info = ref<BuildInfoVO | null>(null)

const shortCommit = computed(() => {
  const c = info.value?.commit
  if (!c) return '-'
  return c.length > 8 ? c.substring(0, 8) : c
})

async function loadVersion() {
  loading.value = true
  try {
    const data = await versionApi.get()
    info.value = data
  } catch (e: any) {
    ElMessage.error('加载版本信息失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function buildFullInfoText(): string {
  if (!info.value) return ''
  const i = info.value
  const lines = [
    `插件版本: ${i.version || '-'}`,
    `插件 ID: ${i.pluginId || '-'}`,
    `插件描述: ${i.pluginDescription || '-'}`,
    `Git Commit: ${i.commit || '-'}`,
    `构建时间: ${i.buildTime || '-'}`,
    `JDK 版本: ${i.jdkVersion || '-'}`,
    `PF4J 版本: ${i.pf4jVersion || '-'}`,
    `Spring Boot 版本: ${i.springBootVersion || '-'}`
  ]
  return lines.join('\n')
}

async function copyText(text: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch (e: any) {
    ElMessage.error('复制失败：' + (e?.message || e))
  }
}

async function copyFullInfo() {
  const text = buildFullInfoText()
  if (!text) {
    ElMessage.warning('版本信息尚未加载')
    return
  }
  await copyText(text)
}

onMounted(() => {
  loadVersion()
})
</script>

<style scoped>
.version-page {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-card {
  background: var(--platform-surface-1);
  border: 1px solid var(--platform-line);
  border-radius: 6px;
  box-shadow: none;
}

.mono-text {
  font-family: 'Consolas', 'Monaco', monospace;
  background: var(--platform-surface-2);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 13px;
}
</style>
