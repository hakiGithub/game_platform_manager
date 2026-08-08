<script setup lang="ts">
/**
 * 插件容器页（Wujie 版）
 * 主应用侧边栏已动态加载插件菜单，本组件只负责：
 * 1. 检查实例选择（未选时弹窗）—— 菜单要求实例时
 * 2. 根据当前菜单路径加载 Wujie 子应用对应页面
 * 不再渲染左侧菜单（避免与主应用侧边栏重复）
 */
import { ref, computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import PluginContainer from "./PluginContainer.vue";
import { usePluginStore } from "../stores/pluginStore";
import { getInstanceList } from "@/api/instance";
import type { PluginMenuItem, ReadyPayload } from "../types/messageTypes";

// Props（由路由 props 注入）
const props = withDefaults(
  defineProps<{
    /** 游戏代码 */
    gameCode: string;
    /** 当前菜单路径（如 dashboard、maps、rcon），缺省 dashboard */
    menuPath?: string;
    /** 实例 ID（来自 URL query，0 表示未选） */
    instanceId: number;
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
  }>(),
  {
    menuPath: "dashboard",
  },
);

// Emits
const emit = defineEmits<{
  /** 插件就绪事件 */
  ready: [payload: ReadyPayload];
  /** 插件错误事件 */
  error: [error: Error];
}>();

const route = useRoute();
const router = useRouter();
const pluginStore = usePluginStore();

// 实例选择对话框
const instanceDialogVisible = ref(false);
const instanceLoading = ref(false);
const instanceList = ref<any[]>([]);

/**
 * 规范化菜单路径为 /xxx 形式（左侧补斜杠）
 */
function normalizeMenuPath(p?: string): string {
  if (!p) return "/dashboard";
  return p.startsWith("/") ? p : `/${p}`;
}

/**
 * 在插件清单中查找当前 menuPath 对应的菜单项
 * 递归搜索 menus 树，按 path 严格匹配
 */
function findMenuByPath(menus: PluginMenuItem[], target: string): PluginMenuItem | null {
  for (const item of menus) {
    if (item.path === target) return item;
    if (item.children && item.children.length > 0) {
      const hit = findMenuByPath(item.children, target);
      if (hit) return hit;
    }
  }
  return null;
}

/**
 * 当前菜单是否要求选中实例
 * - 菜单 manifest 未加载时默认 true（保持原有行为）
 * - 后端未显式提供 requireInstance 时默认 true
 * - 仅当后端显式 requireInstance=false 时（如地图中心）跳过实例选择
 */
const currentMenuRequireInstance = computed(() => {
  const menus = pluginStore.menus;
  if (!menus || menus.length === 0) return true;
  const target = normalizeMenuPath(props.menuPath);
  const menu = findMenuByPath(menus, target);
  if (!menu) return true;
  return menu.requireInstance !== false;
});

// 当前实例信息（来自 query 或弹窗选择后写入 query）
// 对于 requireInstance=false 的菜单，即便没有实例也允许渲染子应用
const currentInstance = computed(() => {
  if (props.instanceId) {
    return {
      id: props.instanceId,
      instanceName: props.instanceName || "",
      hostId: props.hostId || 0,
      hostIp: props.hostIp || "",
      installPath: props.deployPath || "",
      portConfig: props.ports || {},
    };
  }
  // requireInstance=false 时返回占位实例信息，使 Wujie 子应用可正常加载
  if (!currentMenuRequireInstance.value) {
    return {
      id: 0,
      instanceName: "",
      hostId: 0,
      hostIp: "",
      installPath: "",
      portConfig: {} as Record<string, number>,
    };
  }
  return null;
});

// Wujie 子应用 URL
// 子应用在 Wujie 模式下使用 createWebHashHistory()，路由通过 hash 片段传递
const pluginUrl = computed(() => {
  if (!pluginStore.currentManifest || !currentInstance.value) {
    return "";
  }
  const entry = pluginStore.currentManifest.entry;
  // 规范化菜单路径：确保以 / 开头
  const path = normalizeMenuPath(props.menuPath);

  // 如果 entry 是完整 URL，直接拼接 hash
  if (entry.startsWith("http://") || entry.startsWith("https://")) {
    const url = new URL(entry);
    url.hash = path;
    return url.toString();
  }
  // 相对路径，hash 片段传递路由
  return `${entry}#${path}`;
});

/**
 * 处理插件就绪
 */
function handleReady(payload: ReadyPayload) {
  emit("ready", payload);
}

/**
 * 处理插件错误
 */
function handleError(error: Error) {
  emit("error", error);
}

/**
 * 重新加载插件
 */
function reloadPlugin() {
  // 通过改变 key 触发重载（PluginContainer 内部 watch src 会重载）
  reloadKey.value++;
}

const reloadKey = ref(0);

/**
 * 检查实例选择，未选时弹出对话框
 * - requireInstance=false 的菜单跳过实例选择
 * - 已携带 instanceId 时直接复用
 */
async function ensureInstanceOrPrompt() {
  if (!currentMenuRequireInstance.value) return;
  if (props.instanceId) return;
  instanceDialogVisible.value = true;
  instanceLoading.value = true;
  instanceList.value = [];
  try {
    const data = await getInstanceList({
      gameCode: props.gameCode,
      current: 1,
      size: 100,
    });
    instanceList.value = data?.records || [];
    if (instanceList.value.length === 0) {
      ElMessage.warning(
        `未找到游戏编码 ${props.gameCode} 的实例，请先在实例管理中部署`
      );
    }
  } catch (e: any) {
    ElMessage.error("获取实例列表失败：" + (e?.message || ""));
  } finally {
    instanceLoading.value = false;
  }
}

/**
 * 选择实例后，将信息写入 URL query（router.replace 避免历史记录污染）
 */
function handleSelectInstance(instance: any) {
  instanceDialogVisible.value = false;
  router.replace({
    path: route.path,
    query: {
      instanceId: instance.id,
      instanceName: instance.instanceName || "",
      hostId: instance.hostId || 0,
      hostIp: instance.hostIp || "",
      deployPath: instance.installPath || "",
      ports: instance.portConfig
        ? JSON.stringify(instance.portConfig)
        : "{}",
    },
  });
}

/**
 * 实例状态标签类型
 */
function getInstanceStatusType(runStatus: number) {
  switch (runStatus) {
    case 1:
      return "success";
    case 0:
      return "info";
    case 2:
      return "danger";
    case 5:
      return "warning";
    default:
      return "info";
  }
}

/**
 * 实例状态文本
 */
function getInstanceStatusText(runStatus: number) {
  switch (runStatus) {
    case 1:
      return "运行中";
    case 0:
      return "已停止";
    case 2:
      return "异常";
    case 5:
      return "部署中";
    default:
      return "未知";
  }
}

// 监听 gameCode 变化，加载 manifest 并检查实例选择
watch(
  () => props.gameCode,
  async (g) => {
    if (!g) return;
    const manifest = await pluginStore.loadManifest(g);
    // 清单加载失败（如插件未安装）时不再拉取实例，直接展示降级空态
    if (manifest) {
      await ensureInstanceOrPrompt();
    }
  },
  { immediate: true }
);

// 监听 menuPath 变化：从 requireInstance=true 菜单切到 requireInstance=false 菜单时
// 需主动触发实例选择检查的更新（已选实例无需再弹窗，未选且要求实例时弹窗）
watch(
  () => props.menuPath,
  async () => {
    if (!pluginStore.currentManifest) return;
    await ensureInstanceOrPrompt();
  }
);
</script>

<template>
  <div class="plugin-tab">
    <!-- 加载中 -->
    <div v-if="pluginStore.loading" class="plugin-loading">
      <el-icon class="loading-icon" :size="40">
        <Loading />
      </el-icon>
      <p>加载插件配置中...</p>
    </div>

    <!-- 错误提示 -->
    <el-result
      v-else-if="pluginStore.error"
      icon="error"
      title="插件加载失败"
      :sub-title="pluginStore.error"
    />

    <!-- 未选实例提示（仅 requireInstance=true 的菜单展示） -->
    <div v-else-if="!currentInstance" class="plugin-empty">
      <el-empty description="请先选择要管理的实例">
        <el-button type="primary" @click="ensureInstanceOrPrompt">
          选择实例
        </el-button>
      </el-empty>
    </div>

    <!-- Wujie 子应用 -->
    <div v-else class="plugin-container-wrapper">
      <PluginContainer
        :key="`${gameCode}-${menuPath}-${reloadKey}`"
        :src="pluginUrl"
        :instance-id="currentInstance.id"
        :game-code="gameCode"
        :instance-name="currentInstance.instanceName"
        :host-id="currentInstance.hostId"
        :host-ip="currentInstance.hostIp"
        :deploy-path="currentInstance.installPath"
        :ports="currentInstance.portConfig"
        @ready="handleReady"
        @error="handleError"
      />
    </div>

    <!-- 实例选择对话框 -->
    <el-dialog
      v-model="instanceDialogVisible"
      title="选择要管理的实例"
      width="720px"
      destroy-on-close
    >
      <el-alert
        v-if="pluginStore.currentManifest"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      >
        <template #title>
          请选择要使用「{{
            pluginStore.currentManifest?.name
          }}」管理的实例
        </template>
      </el-alert>

      <el-table
        :data="instanceList"
        v-loading="instanceLoading"
        stripe
        max-height="400"
        empty-text="暂无对应游戏实例，请先在实例管理中部署"
        @row-dblclick="handleSelectInstance"
      >
        <el-table-column
          prop="instanceName"
          label="实例名称"
          min-width="140"
          show-overflow-tooltip
        />
        <el-table-column
          prop="hostName"
          label="主机"
          width="120"
          show-overflow-tooltip
        />
        <el-table-column prop="hostIp" label="主机IP" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getInstanceStatusType(row.runStatus)" size="small">
              {{ getInstanceStatusText(row.runStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="installPath"
          label="部署路径"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              size="small"
              link
              @click="handleSelectInstance(row)"
            >
              进入管理
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="instanceDialogVisible = false">取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.plugin-tab {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.plugin-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 300px;

  .loading-icon {
    animation: rotate 1s linear infinite;
    color: var(--el-color-primary);
    margin-bottom: 16px;
  }

  p {
    color: var(--el-text-color-secondary);
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

.plugin-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 400px;
}

.plugin-container-wrapper {
  flex: 1;
  position: relative;
  background: var(--el-bg-color);
  height: 100%;
}
</style>
