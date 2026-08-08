<template>
  <div class="layout-container" :class="{ 'is-wujie': isWujie }">
    <!-- 侧边栏（仅 dev 模式渲染，Wujie 模式由主应用提供菜单） -->
    <div v-if="!isWujie" class="sidebar">
      <div class="sidebar-header">
        <div class="logo">
          <el-icon :size="32"><Monitor /></el-icon>
        </div>
        <div class="title">
          <h2>L4D2</h2>
          <span>服务器管理</span>
        </div>
      </div>

      <el-menu
        :default-active="activeMenu"
        class="sidebar-menu"
        router
      >
        <el-menu-item
          v-for="route in menuRoutes"
          :key="route.path"
          :index="route.path"
        >
          <el-icon>
            <component :is="route.meta?.icon" />
          </el-icon>
          <span>{{ route.meta?.title }}</span>
        </el-menu-item>
      </el-menu>
    </div>

    <!-- 主内容区 -->
    <div class="main-container">
      <!-- 顶部实例指示器（仅 dev 模式显示） -->
      <div v-if="!isWujie" class="instance-bar">
        <div class="instance-info">
          <el-icon :size="16"><Connection /></el-icon>
          <span class="instance-label">当前实例：</span>
          <span class="instance-name">{{ currentInstanceName }}</span>
          <el-tag
            v-if="currentInstanceStatus !== null"
            :type="instanceStatusTagType"
            size="small"
            class="instance-status-tag"
          >
            {{ currentInstanceStatusDesc }}
          </el-tag>
        </div>
      </div>

      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { routes } from '@/router'
import { usePluginStore } from '@/stores/plugin'
import { instanceApi } from '@/api'
import type { InstanceStatusVO } from '@/api'

const route = useRoute()
const router = useRouter()
const pluginStore = usePluginStore()

// Wujie 模式下不渲染侧边栏（菜单由主应用提供）
// 优先读取 store 中由 props 同步的 isWujie，其次 fallback 到 Wujie 全局变量/props
const isWujie = computed(() => {
  if (pluginStore.isWujie) return true
  if (typeof window === 'undefined') return false
  return Boolean(window.__POWERED_BY_WUJIE__) || (window as any).$wujie?.props?.mode === 'wujie'
})

const activeMenu = computed(() => route.path)

const menuRoutes = computed(() =>
  routes.filter(r => r.meta?.title)
)

// ========== 实例指示器 ==========
const instanceStatus = ref<InstanceStatusVO | null>(null)
let statusTimer: number | null = null

const currentInstanceName = computed(() => {
  return pluginStore.instanceInfo?.instanceName || '未选择'
})

const currentInstanceStatus = computed(() => instanceStatus.value?.runStatus ?? null)

const currentInstanceStatusDesc = computed(() => {
  if (!instanceStatus.value) return '加载中'
  return instanceStatus.value.runStatusDesc || '未知'
})

const instanceStatusTagType = computed(() => {
  const rs = instanceStatus.value?.runStatus
  if (rs === 1) return 'success'
  if (rs === 2) return 'warning'
  if (rs === 5 || rs === 6) return 'info'
  return 'info'
})

async function refreshInstanceStatus() {
  // Wujie 模式不查询（实例由主应用管理）
  if (isWujie.value) return
  const instanceId = pluginStore.instanceInfo?.instanceId
  if (!instanceId) {
    instanceStatus.value = null
    return
  }
  try {
    instanceStatus.value = await instanceApi.getStatus(instanceId)
  } catch (e) {
    // 静默失败，保留上次状态
  }
}

onMounted(() => {
  refreshInstanceStatus()
  // 每 30 秒刷新实例状态，与 Dashboard 同步节奏
  statusTimer = window.setInterval(refreshInstanceStatus, 30000)
})

onUnmounted(() => {
  if (statusTimer) {
    clearInterval(statusTimer)
  }
})
</script>

<style lang="scss" scoped>
.layout-container {
  display: flex;
  width: 100%;
  height: 100%;
  background-color: var(--el-bg-color-page);
}

/* Wujie 模式下移除 padding，由主应用控制外层间距 */
.layout-container.is-wujie {
  .main-container {
    padding: 0;
  }

  /* 兜底：即使 JS 判断异常，也强制隐藏子应用自带侧边栏，避免与主应用菜单重复 */
  .sidebar {
    display: none !important;
  }
}

.sidebar {
  width: 220px;
  height: 100%;
  background-color: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-light);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.sidebar-header {
  padding: 20px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--el-border-color-light);

  .logo {
    width: 48px;
    height: 48px;
    background: linear-gradient(135deg, #409eff 0%, #67c23a 100%);
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: white;
  }

  .title {
    h2 {
      margin: 0;
      font-size: 18px;
      color: var(--el-text-color-primary);
    }

    span {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
}

.sidebar-menu {
  flex: 1;
  border-right: none;
  overflow-y: auto;

  :deep(.el-menu-item) {
    height: 48px;
    line-height: 48px;

    &.is-active {
      background-color: var(--el-color-primary-light-9);
    }
  }
}

.main-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
}

/* 顶部实例指示器 */
.instance-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  margin-bottom: 16px;
  background-color: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  flex-shrink: 0;

  .instance-info {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--el-text-color-primary);
    font-size: 14px;
    min-width: 0;

    .instance-label {
      color: var(--el-text-color-secondary);
    }

    .instance-name {
      font-weight: 600;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 280px;
    }

    .instance-status-tag {
      margin-left: 4px;
    }
  }
}

/* Wujie 模式下隐藏实例指示器（实例由主应用管理） */
.layout-container.is-wujie {
  .instance-bar {
    display: none;
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
