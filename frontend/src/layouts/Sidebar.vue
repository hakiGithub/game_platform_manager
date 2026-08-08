<script setup>
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAppStore } from "@/stores/app";
import { getPluginList, getPluginManifest } from "@/api/plugin";

defineProps({
  collapsed: {
    type: Boolean,
    default: false,
  },
});

const route = useRoute();
const router = useRouter();
const appStore = useAppStore();

// 静态菜单（基础功能）
const staticMenuItems = [
  {
    index: "/dashboard",
    icon: "Odometer",
    title: "仪表盘",
  },
  {
    index: "/host",
    icon: "Monitor",
    title: "主机管理",
    children: [{ index: "/host/list", title: "主机列表" }],
  },
  {
    index: "/instance",
    icon: "Grid",
    title: "实例管理",
    children: [{ index: "/instance/list", title: "实例列表" }],
  },
  {
    index: "/game",
    icon: "TrendCharts",
    title: "游戏管理",
    children: [{ index: "/game/list", title: "游戏列表" }],
  },
];

// 动态插件菜单（从后端加载，每个 running 插件作为一级菜单）
const pluginMenuItems = ref([]);

// 任务中心菜单（位于动态插件菜单之后、系统设置之前）
const taskMenuItems = [
  {
    index: "/task",
    icon: "List",
    title: "任务中心",
    children: [{ index: "/task/list", title: "任务列表" }],
  },
];

// 系统菜单（包含插件管理入口）
const systemMenuItems = [
  {
    index: "/system",
    icon: "Setting",
    title: "系统设置",
    children: [
      { index: "/system/settings", title: "系统配置" },
      { index: "/system/logs", title: "系统日志" },
      { index: "/plugins/list", title: "插件管理" },
    ],
  },
];

// 合并菜单：静态 + 动态插件 + 任务中心 + 系统
const menuItems = computed(() => [
  ...staticMenuItems,
  ...pluginMenuItems.value,
  ...taskMenuItems,
  ...systemMenuItems,
]);

/**
 * 从插件ID提取游戏编码
 */
function extractGameCode(pluginId) {
  if (!pluginId) return "";
  if (pluginId.startsWith("plugin-")) {
    return pluginId.substring("plugin-".length);
  }
  return "";
}

/**
 * 加载已启动插件的菜单
 * 每个 running 插件作为一级菜单（标题为插件名），下挂其菜单项作为二级菜单
 */
async function loadPluginMenus() {
  try {
    const plugins = await getPluginList();
    const runningPlugins = (plugins || []).filter((p) => p.running);
    const menus = [];
    for (const plugin of runningPlugins) {
      const gameCode = extractGameCode(plugin.pluginId);
      if (!gameCode) continue;
      try {
        const manifest = await getPluginManifest(gameCode);
        const backendMenus = manifest?.frontend?.menus || [];
        // 菜单项按 order 排序
        const sortedMenus = [...backendMenus].sort(
          (a, b) => (a.order || 0) - (b.order || 0)
        );
        const children = sortedMenus.map((m) => ({
          index: `/plugin/${gameCode}${m.path}`,
          title: m.title,
        }));
        menus.push({
          index: `/plugin/${gameCode}`,
          icon: "Connection",
          title: plugin.pluginName || manifest?.gameName || gameCode,
          children:
            children.length > 0
              ? children
              : [{ index: `/plugin/${gameCode}/dashboard`, title: "仪表盘" }],
        });
      } catch (e) {
        console.error(`Failed to load manifest for ${gameCode}:`, e);
      }
    }
    pluginMenuItems.value = menus;
  } catch (e) {
    console.error("Failed to load plugin menus:", e);
  }
}

// 当前激活菜单
const activeMenu = computed(() => {
  const { path } = route;
  return path;
});

// 默认展开的菜单
const defaultOpeneds = computed(() => {
  const matched = route.matched;
  return matched.map((item) => item.path).filter((path) => path !== route.path);
});

// 菜单点击
function handleSelect(index) {
  // 从插件菜单切到另一个插件菜单时，保留 instanceId 等 query（避免反复弹窗选实例）
  if (
    index.startsWith("/plugin/") &&
    route.path.startsWith("/plugin/")
  ) {
    router.push({ path: index, query: route.query });
  } else {
    router.push(index);
  }
}

onMounted(() => {
  loadPluginMenus();
});
</script>

<template>
  <aside class="sidebar" :class="{ 'is-collapsed': collapsed }">
    <!-- Logo -->
    <div class="sidebar-logo">
      <el-icon v-if="collapsed" :size="24"><Monitor /></el-icon>
      <template v-else>
        <el-icon :size="24"><Monitor /></el-icon>
        <span class="logo-text">游戏服务器管理</span>
      </template>
    </div>

    <!-- 菜单 -->
    <el-scrollbar class="sidebar-menu-wrapper">
      <el-menu
        :default-active="activeMenu"
        :default-openeds="defaultOpeneds"
        :collapse="collapsed"
        :collapse-transition="false"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
        @select="handleSelect"
      >
        <template v-for="item in menuItems" :key="item.index">
          <!-- 有子菜单 -->
          <el-sub-menu v-if="item.children" :index="item.index">
            <template #title>
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </template>
            <el-menu-item
              v-for="child in item.children"
              :key="child.index"
              :index="child.index"
            >
              {{ child.title }}
            </el-menu-item>
          </el-sub-menu>

          <!-- 无子菜单 -->
          <el-menu-item v-else :index="item.index">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.title }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-scrollbar>
  </aside>
</template>

<style lang="scss" scoped>
.sidebar {
  position: fixed;
  top: 0;
  left: 0;
  width: var(--sidebar-width);
  height: 100vh;
  background-color: #304156;
  transition: width var(--transition-duration);
  z-index: 1001;
  overflow: hidden;

  &.is-collapsed {
    width: var(--sidebar-collapsed-width);

    .sidebar-logo {
      padding: 0;
      justify-content: center;
    }

    .logo-text {
      display: none;
    }
  }
}

.sidebar-logo {
  display: flex;
  align-items: center;
  height: var(--header-height);
  padding: 0 var(--spacing-lg);
  background-color: #263445;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  white-space: nowrap;

  .logo-text {
    margin-left: var(--spacing-sm);
  }
}

.sidebar-menu-wrapper {
  height: calc(100vh - var(--header-height));
}

:deep(.el-menu) {
  border-right: none;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  &:hover {
    background-color: #263445 !important;
  }
}

:deep(.el-menu-item.is-active) {
  background-color: #409eff !important;
  color: #fff !important;
}

:deep(.el-sub-menu .el-menu-item.is-active) {
  background-color: #409eff !important;
  color: #fff !important;
}

:deep(.el-menu-item.is-active .el-icon) {
  color: #fff !important;
}
</style>
