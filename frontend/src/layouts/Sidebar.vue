<script setup>
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { getPluginList, getPluginManifest } from "@/api/plugin";

defineProps({
  collapsed: {
    type: Boolean,
    default: false,
  },
});

const route = useRoute();
const router = useRouter();

const workspaceMenuItems = [
  {
    index: "/workspace/overview",
    icon: "Odometer",
    title: "运行总览",
  },
];

const resourceMenuItems = [
  {
    index: "/resources/hosts",
    icon: "Monitor",
    title: "主机资源",
    children: [{ index: "/resources/hosts/list", title: "主机列表" }],
  },
  {
    index: "/resources/containers",
    icon: "Box",
    title: "容器资源",
    children: [{ index: "/resources/containers/list", title: "容器列表" }],
  },
];

const serviceMenuItems = [
  {
    index: "/services/instances",
    icon: "Grid",
    title: "实例服务",
    children: [{ index: "/services/instances/list", title: "实例列表" }],
  },
  {
    index: "/services/games",
    icon: "TrendCharts",
    title: "游戏目录",
    children: [{ index: "/services/games/list", title: "游戏列表" }],
  },
  {
    index: "/services/tasks",
    icon: "List",
    title: "执行队列",
    children: [{ index: "/services/tasks/list", title: "任务列表" }],
  },
];

const staticExtensionMenuItems = [
  {
    index: "/extensions/plugins",
    icon: "Connection",
    title: "插件扩展",
    children: [{ index: "/extensions/plugins/list", title: "插件列表" }],
  },
];

const systemMenuItems = [
  {
    index: "/system",
    icon: "Setting",
    title: "系统设置",
    children: [
      { index: "/system/configuration", title: "系统配置" },
    ],
  },
];

const pluginMenuItems = ref([]);

const extensionMenuItems = computed(() => [
  ...staticExtensionMenuItems,
  ...pluginMenuItems.value,
]);

const menuSections = computed(() => [
  { key: "workspace", label: "工作台", items: workspaceMenuItems },
  { key: "resources", label: "资源管理", items: resourceMenuItems },
  { key: "services", label: "服务编排", items: serviceMenuItems },
  { key: "extensions", label: "扩展中心", items: extensionMenuItems.value },
  { key: "system", label: "平台设置", items: systemMenuItems },
]);

const activeMenu = computed(() => {
  if (route.path.startsWith("/extensions/app/")) {
    const [, , gameCode] = route.path.split("/");
    return `/extensions/app/${gameCode}`;
  }
  return route.meta?.navPath || route.path;
});

const defaultOpeneds = computed(() => {
  const matchedPaths = route.matched
    .map((item) => item.path)
    .filter((path) => path && path !== route.path);
  if (route.path.startsWith("/extensions/app/")) {
    matchedPaths.push(activeMenu.value);
  }
  return [...new Set(matchedPaths)];
});

function extractGameCode(pluginId) {
  if (!pluginId || !pluginId.startsWith("plugin-")) return "";
  return pluginId.substring("plugin-".length);
}

async function loadPluginMenus() {
  try {
    const plugins = await getPluginList();
    const runningPlugins = (plugins || []).filter((plugin) => plugin.running);
    const menus = [];

    for (const plugin of runningPlugins) {
      const gameCode = extractGameCode(plugin.pluginId);
      if (!gameCode) continue;

      try {
        const manifest = await getPluginManifest(gameCode);
        const backendMenus = manifest?.frontend?.menus || [];
        const children = [...backendMenus]
          .sort((a, b) => (a.order || 0) - (b.order || 0))
          .map((menu) => ({
            index: `/extensions/app/${gameCode}${menu.path}`,
            title: menu.title,
            // 透传后端 PluginMenuDeclaration 声明的 icon（如地图管理 Map）
            icon: menu.icon,
          }));

        menus.push({
          index: `/extensions/app/${gameCode}`,
          icon: "Aim",
          title: plugin.pluginName || manifest?.gameName || gameCode,
          children: children.length
            ? children
            : [{ index: `/extensions/app/${gameCode}/dashboard`, title: "仪表盘" }],
        });
      } catch (error) {
        console.error(`Failed to load manifest for ${gameCode}:`, error);
      }
    }

    pluginMenuItems.value = menus;
  } catch (error) {
    console.error("Failed to load plugin menus:", error);
  }
}

function handleSelect(index) {
  if (index.startsWith("/extensions/app/") && route.path.startsWith("/extensions/app/")) {
    router.push({ path: index, query: route.query });
    return;
  }
  router.push(index);
}

onMounted(loadPluginMenus);
</script>

<template>
  <aside class="sidebar" :class="{ 'is-collapsed': collapsed }">
    <div class="sidebar-logo">
      <div class="logo-mark"><el-icon :size="22"><Monitor /></el-icon></div>
      <div class="logo-copy">
        <span class="logo-text">游戏服务器管理</span>
        <span class="logo-meta">NIGHT OPS · 1.0</span>
      </div>
    </div>

    <el-scrollbar class="sidebar-menu-wrapper">
      <el-menu
        class="sidebar-menu"
        :default-active="activeMenu"
        :default-openeds="defaultOpeneds"
        :collapse="collapsed"
        :collapse-transition="false"
        background-color="transparent"
        text-color="var(--el-text-color-secondary)"
        active-text-color="var(--platform-cyan)"
        @select="handleSelect"
      >
        <div v-for="section in menuSections" :key="section.key" class="menu-section">
          <div class="menu-section-label">{{ section.label }}</div>

          <template v-for="item in section.items" :key="item.index">
            <el-sub-menu v-if="item.children" :index="item.index">
              <template #title>
                <el-icon><component :is="item.icon" /></el-icon>
                <span>{{ item.title }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.index" :index="child.index">
                <el-icon v-if="child.icon"><component :is="child.icon" /></el-icon>
                <template #title>{{ child.title }}</template>
              </el-menu-item>
            </el-sub-menu>

            <el-menu-item v-else :index="item.index">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </template>
        </div>
      </el-menu>
    </el-scrollbar>

  </aside>
</template>

<style lang="scss" scoped>
.sidebar {
  position: fixed;
  top: 0;
  left: 0;
  z-index: 1001;
  display: flex;
  flex-direction: column;
  width: var(--sidebar-width);
  height: 100vh;
  color: var(--el-text-color-primary);
  background: linear-gradient(180deg, #0c1d2a 0%, #0a1824 100%);
  border-right: 1px solid var(--platform-line);
  transition: width var(--transition-duration);
  overflow: hidden;

  &.is-collapsed {
    width: var(--sidebar-collapsed-width);

    .sidebar-logo {
      justify-content: center;
      padding: 0;
    }

    .logo-copy {
      display: none;
    }

    .sidebar-menu-wrapper {
      padding: 10px 0 0;
    }

    .menu-section {
      display: flex;
      flex-direction: column;
      align-items: center;
      margin-bottom: 8px;
      padding-bottom: 8px;
      border-bottom: 1px solid rgba(38, 56, 71, 0.72);
    }

    .menu-section:last-child {
      margin-bottom: 0;
      border-bottom: 0;
    }

    .menu-section-label {
      display: none;
    }
  }
}

.sidebar-logo {
  display: flex;
  flex: 0 0 var(--header-height);
  align-items: center;
  gap: 12px;
  height: var(--header-height);
  padding: 0 18px;
  background: var(--platform-topbar);
  border-bottom: 1px solid var(--platform-line);
  white-space: nowrap;
}

.logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  color: var(--platform-cyan);
  background: rgba(39, 181, 243, 0.1);
  border: 1px solid rgba(39, 181, 243, 0.36);
  border-radius: 8px;
}

.logo-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.logo-text {
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.logo-meta,
.footer-user-role {
  color: var(--el-text-color-placeholder);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.14em;
}

.sidebar-menu-wrapper {
  flex: 1 1 auto;
  min-height: 0;
  height: auto;
  padding: 14px 8px 0;
  overflow-x: hidden;
  overscroll-behavior-x: none;
}

:deep(.sidebar-menu-wrapper .el-scrollbar__wrap) {
  height: 100%;
  overflow-x: hidden !important;
  overscroll-behavior-x: none;
}

:deep(.sidebar-menu-wrapper .el-scrollbar__view) {
  min-height: 100%;
  overflow-x: hidden;
}

.sidebar-menu {
  width: 100%;
  border-right: 0;
}

.menu-section {
  margin-bottom: 12px;
}

.menu-section-label {
  padding: 0 14px 7px;
  color: var(--el-text-color-placeholder);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.16em;
  line-height: 18px;
  text-transform: uppercase;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  height: 40px;
  margin: 2px 0;
  border: 1px solid transparent;
  border-radius: 6px;
  line-height: 40px;
  transition: color var(--transition-duration-fast), background var(--transition-duration-fast), border-color var(--transition-duration-fast);
}

:deep(.el-menu-item:hover),
:deep(.el-sub-menu__title:hover) {
  color: var(--el-text-color-primary) !important;
  background: rgba(39, 181, 243, 0.08) !important;
  border-color: rgba(39, 181, 243, 0.14);
}

:deep(.el-menu-item.is-active) {
  color: var(--platform-cyan) !important;
  background: linear-gradient(90deg, rgba(39, 181, 243, 0.18), rgba(39, 181, 243, 0.06)) !important;
  border-color: rgba(39, 181, 243, 0.24);
  box-shadow: inset 2px 0 0 var(--platform-cyan);
}

:deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--platform-cyan) !important;
}

:deep(.el-sub-menu .el-menu) {
  background: rgba(4, 13, 21, 0.25) !important;
}

:deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  padding-left: 50px !important;
  color: var(--el-text-color-secondary);
}

:deep(.el-menu--collapse) {
  width: 100%;
}

:deep(.el-menu--collapse .menu-section-label) {
  display: none;
}

.sidebar.is-collapsed {
  :deep(.el-menu--collapse .el-menu-item),
  :deep(.el-menu--collapse .el-sub-menu),
  :deep(.el-menu--collapse .el-sub-menu__title) {
    width: 44px;
    min-width: 44px;
    height: 40px;
    margin: 2px auto;
    padding: 0 !important;
    border-radius: 8px;
    line-height: 40px;
  }

  :deep(.el-menu--collapse .el-menu-item),
  :deep(.el-menu--collapse .el-sub-menu__title) {
    justify-content: center;
  }

  :deep(.el-menu--collapse .el-menu-tooltip__trigger) {
    width: 100%;
    padding: 0 !important;
    justify-content: center;
  }

  :deep(.el-menu--collapse .el-menu-item .el-icon),
  :deep(.el-menu--collapse .el-sub-menu__title .el-icon) {
    margin: 0;
  }

  :deep(.el-menu--collapse .el-sub-menu__icon-arrow) {
    display: none;
  }

  :deep(.el-menu--collapse .el-sub-menu__title > span) {
    display: none;
  }

  :deep(.el-menu--collapse .el-sub-menu__title) {
    overflow: hidden;
  }

  :deep(.el-menu--collapse .el-menu-item.is-active),
  :deep(.el-menu--collapse .el-sub-menu.is-active > .el-sub-menu__title) {
    background: linear-gradient(135deg, rgba(39, 181, 243, 0.22), rgba(39, 181, 243, 0.08)) !important;
    border-color: rgba(39, 181, 243, 0.42);
    box-shadow: inset 2px 0 0 var(--platform-cyan), 0 4px 12px rgba(0, 0, 0, 0.16);
  }
}

</style>
