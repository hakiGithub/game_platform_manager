<template>
  <div class="plugins-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / SOURCEMOD PLUGINS</span>
        <h2>插件管理</h2>
        <p>管理服务器 SourceMod 插件的安装、启用与导出；从内置清单或远端仓库获取插件</p>
      </div>
    </div>

    <!-- 统一插件任务反馈区（ADR-0022 决策 4）：任意 tab 发起的安装/下载均在此可见 -->
    <PluginTaskStrip
      :builtin="builtinProgress"
      :remote-tasks="remoteTasks"
      @task-completed="refreshPlugins"
    />

    <el-tabs v-model="activeTab" class="market-tabs">
      <el-tab-pane label="已安装" name="installed">
        <InstalledPluginsTab
          :plugins="plugins"
          :loading="loading"
          @refresh="refreshPlugins"
        />
      </el-tab-pane>
      <el-tab-pane label="内置插件" name="builtin" lazy>
        <BuiltinMarketTab
          @plugins-changed="refreshPlugins"
          @install-progress="onInstallProgress"
        />
      </el-tab-pane>
      <el-tab-pane label="远端仓库" name="remote" lazy>
        <RemoteStoreTab
          :installed-names="installedNames"
          @tasks-update="onRemoteTasks"
          @plugins-changed="refreshPlugins"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { pluginManageApi } from '@/api'
import type { PluginListVO } from '@/api'
import InstalledPluginsTab from '@/components/plugins/InstalledPluginsTab.vue'
import BuiltinMarketTab from '@/components/plugins/BuiltinMarketTab.vue'
import RemoteStoreTab from '@/components/plugins/RemoteStoreTab.vue'
import PluginTaskStrip, { type StripRemoteTask } from '@/components/plugins/PluginTaskStrip.vue'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const activeTab = ref('installed')

const loading = ref(false)
const plugins = ref<PluginListVO[]>([])

/** 内置安装任务进度（任务反馈区展示） */
const builtinProgress = ref({ active: false, progress: 0, title: '' })
/** 远端下载任务（任务反馈区展示，由远端 tab 轮询上报） */
const remoteTasks = ref<StripRemoteTask[]>([])

/** 已安装插件名列表（供内置/远端 tab 名称匹配"已安装"角标） */
const installedNames = computed(() => plugins.value.map(p => p.name))

async function refreshPlugins() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  loading.value = true
  try {
    const data = await pluginManageApi.list(instanceId.value)
    plugins.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('获取插件列表失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

function onInstallProgress(payload: { active: boolean; progress: number; title: string }) {
  builtinProgress.value = payload
}

function onRemoteTasks(tasks: StripRemoteTask[]) {
  remoteTasks.value = tasks
}

onMounted(() => {
  refreshPlugins()
})
</script>

<style lang="scss" scoped>
.plugins-page {
  height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.market-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 12px;
  }

  :deep(.el-tabs__content) {
    overflow: visible;
  }
}
</style>
