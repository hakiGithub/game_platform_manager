<template>
  <div class="server-info-page">
    <!-- 页头：kicker + 标题 + 描述 + 刷新 -->
    <section class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / SERVER INFO</span>
        <h2>服务器信息</h2>
        <p>hostname、MOTD 与主机信息的在线编辑</p>
      </div>
      <div class="header-actions">
        <el-button size="small" :loading="loading" @click="loadAll">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </section>

    <section class="server-grid" v-loading="loading">
      <!-- 服务器名称（全宽） -->
      <el-card class="workspace-card identity-card" shadow="never">
        <template #header>
          <div class="workspace-header">
            <div class="header-copy">
              <span class="section-kicker">SERVER IDENTITY</span>
              <h3>服务器名称</h3>
              <small>hostname · 浏览器服务器列表显示的名称</small>
            </div>
            <el-button
              type="primary"
              size="small"
              :loading="hostnameSaving"
              @click="saveHostname"
            >
              保存
            </el-button>
          </div>
        </template>
        <div class="identity-body">
          <el-input
            v-model="hostname"
            maxlength="64"
            show-word-limit
            clearable
            placeholder="输入服务器名称，如：L4D2 中国区 1 号服"
          />
          <p class="field-hint">玩家加入服务器时在浏览器列表与游戏内可见的名称。</p>
        </div>
      </el-card>

      <!-- MOTD 与 Host 并排 -->
      <el-card class="workspace-card message-card" shadow="never">
        <template #header>
          <div class="workspace-header">
            <div class="header-copy">
              <span class="section-kicker">MOTD</span>
              <h3>进服公告</h3>
              <small>motd.txt · 玩家加入服务器时显示的公告</small>
            </div>
            <el-button
              type="primary"
              size="small"
              :loading="motdSaving"
              @click="saveMotd"
            >
              保存
            </el-button>
          </div>
        </template>
        <el-input
          v-model="motd"
          type="textarea"
          :rows="6"
          resize="none"
          placeholder="输入进服公告内容"
        />
      </el-card>

      <el-card class="workspace-card message-card" shadow="never">
        <template #header>
          <div class="workspace-header">
            <div class="header-copy">
              <span class="section-kicker">HOST INFO</span>
              <h3>主机信息</h3>
              <small>host.txt · 服务器主机信息（如官网 / 联系）</small>
            </div>
            <el-button
              type="primary"
              size="small"
              :loading="hostSaving"
              @click="saveHost"
            >
              保存
            </el-button>
          </div>
        </template>
        <el-input
          v-model="host"
          type="textarea"
          :rows="6"
          resize="none"
          placeholder="输入主机信息内容"
        />
      </el-card>
    </section>

    <!-- 版本信息（折叠面板，懒加载） -->
    <el-collapse v-model="activeCollapse" @change="handleVersionCollapse">
      <el-collapse-item name="version" title="版本信息（插件构建 / 提交 / 运行环境）">
        <div class="version-block">
          <div class="version-actions">
            <el-button size="small" :loading="versionLoading" @click="loadVersion">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
            <el-button size="small" @click="copyFullInfo">
              <el-icon><CopyDocument /></el-icon>
              复制完整版本信息
            </el-button>
          </div>
          <el-skeleton :loading="versionLoading" :rows="6" animated>
            <template #default>
              <el-descriptions v-if="versionInfo" :column="2" border size="small">
                <el-descriptions-item label="插件版本">
                  <el-tag type="success" size="small">{{ versionInfo.version || '-' }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="插件 ID">
                  <code class="mono-text">{{ versionInfo.pluginId || '-' }}</code>
                </el-descriptions-item>
                <el-descriptions-item label="插件描述" :span="2">
                  {{ versionInfo.pluginDescription || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="Git Commit">
                  <code class="mono-text">{{ shortCommit }}</code>
                  <el-button
                    v-if="versionInfo.commit"
                    link
                    size="small"
                    style="margin-left: 8px"
                    @click="copyText(versionInfo.commit)"
                  >
                    <el-icon><CopyDocument /></el-icon>
                    复制
                  </el-button>
                </el-descriptions-item>
                <el-descriptions-item label="构建时间">
                  {{ versionInfo.buildTime || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="JDK 版本">
                  {{ versionInfo.jdkVersion || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="PF4J 版本">
                  {{ versionInfo.pf4jVersion || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="Spring Boot 版本" :span="2">
                  {{ versionInfo.springBootVersion || '-' }}
                </el-descriptions-item>
              </el-descriptions>
            </template>
          </el-skeleton>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { serverInfoApi, versionApi, type BuildInfoVO } from '@/api'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const hostname = ref('')
const motd = ref('')
const host = ref('')

const loading = ref(false)
const hostnameSaving = ref(false)
const motdSaving = ref(false)
const hostSaving = ref(false)

// ===== 版本信息（折叠面板，懒加载：首次展开才请求） =====
const activeCollapse = ref<string[]>([])
const versionInfo = ref<BuildInfoVO | null>(null)
const versionLoading = ref(false)
const versionLoaded = ref(false)

const shortCommit = computed(() => {
  const c = versionInfo.value?.commit
  if (!c) return '-'
  return c.length > 8 ? c.substring(0, 8) : c
})

async function loadVersion() {
  if (versionLoaded.value) return
  versionLoading.value = true
  try {
    versionInfo.value = await versionApi.get()
    versionLoaded.value = true
  } catch (e: any) {
    ElMessage.error('加载版本信息失败：' + (e?.message || e))
  } finally {
    versionLoading.value = false
  }
}

/** 展开版本面板时触发首次加载 */
function handleVersionCollapse(names: import('element-plus').CollapseModelValue) {
  const list = Array.isArray(names) ? names.map(String) : [String(names)]
  if (list.includes('version')) {
    loadVersion()
  }
}

function buildFullInfoText(): string {
  if (!versionInfo.value) return ''
  const i = versionInfo.value
  return [
    `插件版本: ${i.version || '-'}`,
    `插件 ID: ${i.pluginId || '-'}`,
    `插件描述: ${i.pluginDescription || '-'}`,
    `Git Commit: ${i.commit || '-'}`,
    `构建时间: ${i.buildTime || '-'}`,
    `JDK 版本: ${i.jdkVersion || '-'}`,
    `PF4J 版本: ${i.pf4jVersion || '-'}`,
    `Spring Boot 版本: ${i.springBootVersion || '-'}`,
  ].join('\n')
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

async function loadAll() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  loading.value = true
  try {
    const data = await serverInfoApi.get(instanceId.value)
    hostname.value = data?.hostname || ''
    motd.value = data?.motd || ''
    host.value = data?.host || ''
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function saveHostname() {
  if (!instanceId.value) return
  hostnameSaving.value = true
  try {
    await serverInfoApi.update({ instanceId: instanceId.value, hostname: hostname.value })
    ElMessage.success('hostname 已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    hostnameSaving.value = false
  }
}

async function saveMotd() {
  if (!instanceId.value) return
  motdSaving.value = true
  try {
    await serverInfoApi.update({ instanceId: instanceId.value, motd: motd.value })
    ElMessage.success('motd 已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    motdSaving.value = false
  }
}

async function saveHost() {
  if (!instanceId.value) return
  hostSaving.value = true
  try {
    await serverInfoApi.update({ instanceId: instanceId.value, host: host.value })
    ElMessage.success('host 已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    hostSaving.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped lang="scss">
.server-info-page {
  padding: 16px;
}

// 编辑区网格：服务器名称全宽，MOTD / Host 并排
.server-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;

  .identity-card {
    grid-column: 1 / -1;
  }
}

// workspace-card / workspace-header 样式为全局定义（styles/index.scss）
.identity-body {
  .field-hint {
    margin-top: 8px;
    color: var(--platform-text-muted);
    font-size: 12px;
  }
}

// 版本信息折叠面板
.version-block {
  .version-actions {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;
  }

  .mono-text {
    font-family: 'Consolas', 'Monaco', monospace;
    background: var(--platform-surface-2);
    padding: 2px 6px;
    border-radius: 3px;
    font-size: 13px;
  }
}

:deep(.el-input__wrapper),
:deep(.el-textarea__inner) {
  border-radius: 6px;
  background: var(--platform-bg-input, var(--el-bg-color));
}

:deep(.el-input__inner::placeholder),
:deep(.el-textarea__inner::placeholder) {
  color: var(--platform-text-muted);
  opacity: 0.7;
}

// 响应式：窄屏时 MOTD / Host 改为单列
@media (max-width: 1100px) {
  .server-grid {
    grid-template-columns: 1fr;

    .identity-card {
      grid-column: auto;
    }
  }
}
</style>
