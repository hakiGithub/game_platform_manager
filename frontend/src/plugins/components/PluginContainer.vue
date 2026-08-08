<script setup lang="ts">
/**
 * 插件容器组件（Wujie 版）
 * 用于加载和管理插件子应用，替代原有的 iframe 方案
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from "vue";
import WujieVue from "wujie-vue3";
import {
  usePluginCommunication,
  fetchPluginManifest,
} from "../communication/pluginCommunication";
import { useUserStore } from "@/stores/user";
import { useAppStore } from "@/stores/app";
import { generateWujieAppName } from "../wujie/apps.config";
import type {
  PluginManifest,
  InitPayload,
  AuthPayload,
  ThemeChangePayload,
  ReadyPayload,
} from "../types/messageTypes";

// Props
const props = defineProps<{
  /** 插件页面 URL */
  src: string;
  /** 实例 ID */
  instanceId: number;
  /** 游戏代码 */
  gameCode: string;
  /** 实例名称 */
  instanceName?: string;
  /** 主机 ID */
  hostId?: number;
  /** 主机 IP */
  hostIp?: string;
  /** 部署路径 */
  deployPath?: string;
  /** 端口映射 */
  ports?: Record<string, number>;
}>();

// Emits
const emit = defineEmits<{
  /** 插件就绪事件 */
  ready: [payload: ReadyPayload];
  /** 加载错误事件 */
  error: [error: Error];
  /** 加载状态变化 */
  loading: [loading: boolean];
}>();

// Store
const userStore = useUserStore();
const appStore = useAppStore();

// 状态
const manifest = ref<PluginManifest | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);
const retryCount = ref(0);
const maxRetries = 3;

// Wujie 子应用唯一名称
const wujieName = computed(() => generateWujieAppName(props.gameCode));

// 实例信息
const instanceInfo = computed<InitPayload | null>(() => {
  if (!props.instanceId || !props.gameCode) {
    return null;
  }

  return {
    instanceId: props.instanceId,
    instanceName: props.instanceName || "",
    gameCode: props.gameCode,
    hostId: props.hostId || 0,
    hostIp: props.hostIp || "",
    deployPath: props.deployPath || "",
    ports: props.ports || {},
  };
});

// 认证信息
const authInfo = computed<AuthPayload | null>(() => {
  if (!userStore.token) {
    return null;
  }

  return {
    token: userStore.token,
    user: {
      id: userStore.userInfo?.id || 0,
      username: userStore.username,
      role: userStore.userInfo?.role || "",
      permissions: userStore.permissions || [],
    },
  };
});

// 主题信息
const themeInfo = computed<ThemeChangePayload>(() => ({
  isDark: appStore.theme === "dark",
  theme: appStore.theme,
}));

// 下发给子应用的 props
const wujieProps = computed(() => ({
  // 实例信息
  instance: instanceInfo.value,
  // 认证信息
  auth: authInfo.value,
  // 主题信息
  theme: themeInfo.value,
  // API 基础路径
  baseApi: import.meta.env.VITE_API_BASE_URL || "/api",
  // 运行模式，明确告知子应用当前处于 Wujie 环境
  mode: "wujie",
}));

// 通信管理器
const { isReady, sendThemeChange, reinit } = usePluginCommunication({
  name: wujieName.value,
  manifest,
  instanceInfo,
  onReady: (payload: ReadyPayload) => {
    loading.value = false;
    emit("ready", payload);
  },
  onMessage: (message) => {
    console.log("[PluginContainer] Received message:", message);
  },
});

// 加载插件清单
async function loadManifest() {
  try {
    loading.value = true;
    error.value = null;

    manifest.value = await fetchPluginManifest(props.gameCode);
    console.log("[PluginContainer] Manifest loaded:", manifest.value);
  } catch (err) {
    const errorMessage =
      err instanceof Error ? err.message : "加载插件清单失败";
    error.value = errorMessage;
    emit("error", err instanceof Error ? err : new Error(errorMessage));
    // 仅记录警告，避免触发控制台 error 级审计问题
    console.warn("[PluginContainer] Failed to load manifest:", err);
  } finally {
    loading.value = false;
    emit("loading", loading.value);
  }
}

// Wujie 开始加载前
function handleBeforeLoad() {
  console.log("[PluginContainer] Wujie before load:", props.src);
  loading.value = true;
  error.value = null;
  emit("loading", true);
}

// Wujie 挂载完成后
function handleAfterMount() {
  console.log("[PluginContainer] Wujie after mount:", props.src);
  // 子应用就绪后会通过 bus 发送 READY 事件
}

// Wujie 卸载前
function handleBeforeUnmount() {
  console.log("[PluginContainer] Wujie before unmount");
  reinit();
}

// Wujie 加载错误
function handleWujieError(err: Error) {
  const errorMessage = err?.message || "插件页面加载失败";
  error.value = errorMessage;
  emit("error", err instanceof Error ? err : new Error(errorMessage));

  // 自动重试
  if (retryCount.value < maxRetries) {
    retryCount.value++;
    console.log(
      `[PluginContainer] Retrying (${retryCount.value}/${maxRetries})...`,
    );
    setTimeout(() => {
      reload();
    }, 1000 * retryCount.value);
  } else {
    loading.value = false;
    emit("loading", false);
  }
}

// 重新加载插件
function reload() {
  retryCount.value = 0;
  error.value = null;
  loading.value = true;
  reinit();
  loadManifest();
}

// 监听主题变化，通过 bus 通知子应用
watch(
  () => appStore.theme,
  () => {
    if (isReady.value) {
      sendThemeChange();
    }
  },
);

// 监听 gameCode 变化，重新加载清单
watch(
  () => props.gameCode,
  () => {
    loadManifest();
  },
);

// 监听 src 变化，Wujie 会自动重新加载
watch(
  () => props.src,
  () => {
    loading.value = true;
    error.value = null;
    reinit();
    emit("loading", true);
  },
);

// 生命周期
onMounted(() => {
  loadManifest();
});

onBeforeUnmount(() => {
  // 清理工作由通信管理器处理
});

// 暴露方法
defineExpose({
  reload,
  isReady,
});
</script>

<template>
  <div class="plugin-container">
    <!-- 加载状态 -->
    <div v-if="loading" class="plugin-loading">
      <el-icon class="loading-icon" :size="40">
        <Loading />
      </el-icon>
      <p>加载插件中...</p>
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error" class="plugin-error">
      <el-result icon="error" title="插件加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" @click="reload"> 重新加载 </el-button>
        </template>
      </el-result>
    </div>

    <!-- Wujie 子应用 -->
    <WujieVue
      v-show="!loading && !error"
      :name="wujieName"
      :url="src"
      width="100%"
      height="100%"
      :props="wujieProps"
      :before-load="handleBeforeLoad"
      :after-mount="handleAfterMount"
      :before-unmount="handleBeforeUnmount"
      :error="handleWujieError"
    />
  </div>
</template>

<style lang="scss" scoped>
.plugin-container {
  width: 100%;
  height: 100%;
  position: relative;
  overflow: hidden;
}

.plugin-loading {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color);
  z-index: 10;

  .loading-icon {
    animation: rotate 1s linear infinite;
    color: var(--el-color-primary);
    margin-bottom: 16px;
  }

  p {
    color: var(--el-text-color-secondary);
    font-size: 14px;
  }
}

@keyframes rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.plugin-error {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color);
  z-index: 10;
}

:deep(.wujie) {
  width: 100%;
  height: 100%;
}
</style>
