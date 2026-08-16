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
import { statusType } from "@/utils/instanceStatus";
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
    /** 实例名称（兼容旧版 query，新链路仅传 instanceId，由反查补全） */
    instanceName?: string;
    /** 主机 ID（兼容旧版 query） */
    hostId?: number;
    /** 主机 IP（兼容旧版 query） */
    hostIp?: string;
    /** 部署路径（兼容旧版 query） */
    deployPath?: string;
    /** 端口映射（兼容旧版 query） */
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

// 反查补全的当前实例信息（按 instanceId 从实例列表匹配）
// （instanceList 已在实例选择对话框声明，兼作切换下拉数据源）
const resolvedInstance = ref<any>(null);

// 当前实例信息：优先反查补全（仅 query 传 instanceId），回退旧版 props 字段
// 对于 requireInstance=false 的菜单，即便没有实例也允许渲染子应用
const currentInstance = computed(() => {
  const inst = resolvedInstance.value;
  if (inst) {
    return {
      id: inst.id,
      instanceName: inst.instanceName || "",
      hostId: inst.hostId || 0,
      hostIp: inst.hostIp || "",
      installPath: inst.installPath || "",
      portConfig: inst.portConfig || {},
    };
  }
  if (props.instanceId) {
    // 反查未命中（实例可能已被删除）或列表未加载：回退 props 兼容旧 query
    return {
      id: props.instanceId,
      instanceName: props.instanceName || "",
      hostId: props.hostId || 0,
      hostIp: props.hostIp || "",
      installPath: props.deployPath || "",
      portConfig: props.ports || ({} as Record<string, number>),
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

// 当前实例运行状态（头部状态点用）
const currentInstanceStatus = computed(() => {
  const inst = resolvedInstance.value;
  return inst ? statusType(inst.status ?? inst.runStatus) : "";
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
 * 实例选择检查：
 * - requireInstance=false 的菜单跳过实例选择
 * - 已携带 instanceId：按 ID 反查补全实例信息（切换下拉数据一并加载）
 * - 未选：0 个提示部署、1 个默认选中直接进入、多个弹窗选择
 */
async function ensureInstanceOrPrompt() {
  if (!currentMenuRequireInstance.value) return;
  instanceLoading.value = true;
  try {
    const data = await getInstanceList({
      gameCode: props.gameCode,
      current: 1,
      size: 100,
    });
    instanceList.value = data?.records || [];

    if (props.instanceId) {
      // 已选：反查补全（instanceId 可能已失效，未命中时回退 props）
      resolvedInstance.value =
        instanceList.value.find((i) => i.id === props.instanceId) || null;
      return;
    }

    if (instanceList.value.length === 0) {
      ElMessage.warning(
        `未找到游戏编码 ${props.gameCode} 的实例，请先在实例管理中部署`
      );
      return;
    }
    if (instanceList.value.length === 1) {
      // 唯一实例：默认选中直接进入（仅写 instanceId，避免 query 膨胀）
      selectInstance(instanceList.value[0]);
      return;
    }

    // 多个实例：弹窗选择
    instanceDialogVisible.value = true;
  } catch (e: any) {
    ElMessage.error("获取实例列表失败：" + (e?.message || ""));
  } finally {
    instanceLoading.value = false;
  }
}

/**
 * 选中实例：写入 URL query（仅 instanceId，其余由反查补全）
 */
function selectInstance(instance: any) {
  if (!instance) return;
  instanceDialogVisible.value = false;
  router.replace({
    path: route.path,
    query: { instanceId: instance.id },
  });
}

/** 弹窗选择（复用 selectInstance） */
function handleSelectInstance(instance: any) {
  selectInstance(instance);
}

/**
 * 头部切换实例：更新 query + 重载子应用（gameCode/menuPath 不变时
 * PluginContainer key 不变，需手动触发 reload）
 */
function handleSwitchInstance(instanceId: number) {
  const id = Number(instanceId);
  if (!id || id === currentInstance.value?.id) return;
  router.replace({
    path: route.path,
    query: { instanceId: id },
  });
  reloadPlugin();
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

// 监听 instanceId 变化（切换实例 / URL 直接修改）：重新反查补全实例信息
// （子应用重载由 handleSwitchInstance 显式触发，此处仅更新头部显示与下拉选中态）
watch(
  () => props.instanceId,
  async (newId) => {
    if (!pluginStore.currentManifest || !newId) return;
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

    <!-- 当前实例指示条（仅要求实例的菜单展示）：状态点 + 实例名 + 切换下拉 -->
    <div
      v-else-if="currentMenuRequireInstance && currentInstance"
      class="plugin-instance-bar"
    >
      <div class="instance-bar-left">
        <span class="instance-dot" :class="`is-${currentInstanceStatus || 'info'}`" />
        <span class="instance-name">{{ currentInstance.instanceName || '未命名实例' }}</span>
      </div>
      <el-dropdown
        v-if="instanceList.length > 1"
        trigger="click"
        @command="handleSwitchInstance"
      >
        <el-button size="small" text class="instance-switch-btn">
          切换实例
          <el-icon><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="inst in instanceList"
              :key="inst.id"
              :command="inst.id"
              :disabled="inst.id === currentInstance.id"
            >
              <span class="instance-option">
                <span class="instance-dot" :class="`is-${statusType(inst.status ?? inst.runStatus) || 'info'}`" />
                {{ inst.instanceName }}
              </span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
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
            <el-tag :type="statusType(row.status)" size="small">
              {{ row.runStatusDesc }}
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

.plugin-instance-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color-page);

  .instance-bar-left {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .instance-name {
    font-size: 14px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .instance-switch-btn {
    color: var(--el-text-color-secondary);
  }
}

.instance-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;

  &.is-success { background: #67c23a; }
  &.is-danger { background: #f56c6c; }
  &.is-warning { background: #e6a23c; }
  &.is-info { background: #909399; }
}

.instance-option {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
