<script setup lang="ts">
/**
 * 插件菜单组件（Wujie 版）
 * 根据插件清单动态渲染侧边栏菜单
 * 菜单切换后由 PluginTab 计算子应用 URL 并通过 Wujie 加载
 */
import { computed } from "vue";
import { usePluginStore } from "../stores/pluginStore";
import type { PluginMenuItem } from "../types/messageTypes";

// Emits
const emit = defineEmits<{
  /** 菜单点击事件 */
  select: [menu: PluginMenuItem];
}>();

// Store
const pluginStore = usePluginStore();

// 计算属性
const menus = computed(() => pluginStore.menus);
const activeMenuId = computed(() => pluginStore.activeMenuId);
const hasMenus = computed(() => pluginStore.hasMenus);

/**
 * 处理菜单点击
 */
function handleMenuClick(menu: PluginMenuItem) {
  pluginStore.setActiveMenu(menu.id);
  emit("select", menu);
}

/**
 * 判断菜单是否激活
 */
function isActive(menu: PluginMenuItem): boolean {
  return activeMenuId.value === menu.id;
}

/**
 * 获取菜单图标
 */
function getMenuIcon(icon?: string): string {
  return icon || "Document";
}
</script>

<template>
  <div v-if="hasMenus" class="plugin-menu">
    <div class="plugin-menu-header">
      <el-icon><Connection /></el-icon>
      <span>插件功能</span>
    </div>

    <el-menu
      :default-active="activeMenuId || undefined"
      class="plugin-menu-list"
      @select="
        (index: string) => {
          const menu = menus.find((m) => m.id === index);
          if (menu) handleMenuClick(menu);
        }
      "
    >
      <template v-for="menu in menus" :key="menu.id">
        <!-- 有子菜单的情况 -->
        <el-sub-menu
          v-if="menu.children && menu.children.length > 0"
          :index="menu.id"
        >
          <template #title>
            <el-icon><component :is="getMenuIcon(menu.icon)" /></el-icon>
            <span>{{ menu.label }}</span>
          </template>

          <el-menu-item
            v-for="child in menu.children"
            :key="child.id"
            :index="child.id"
            :class="{ 'is-active': isActive(child) }"
          >
            <el-icon><component :is="getMenuIcon(child.icon)" /></el-icon>
            <span>{{ child.label }}</span>
          </el-menu-item>
        </el-sub-menu>

        <!-- 没有子菜单的情况 -->
        <el-menu-item
          v-else
          :index="menu.id"
          :class="{ 'is-active': isActive(menu) }"
        >
          <el-icon><component :is="getMenuIcon(menu.icon)" /></el-icon>
          <span>{{ menu.label }}</span>
        </el-menu-item>
      </template>
    </el-menu>
  </div>
</template>

<style lang="scss" scoped>
.plugin-menu {
  margin-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 16px;
}

.plugin-menu-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-secondary);

  .el-icon {
    font-size: 16px;
  }
}

.plugin-menu-list {
  border-right: none;

  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    height: 44px;
    line-height: 44px;

    .el-icon {
      font-size: 18px;
      margin-right: 8px;
    }
  }

  :deep(.el-menu-item.is-active) {
    background-color: var(--el-color-primary-light-9);
    color: var(--el-color-primary);

    .el-icon {
      color: var(--el-color-primary);
    }
  }

  :deep(.el-sub-menu .el-menu-item) {
    padding-left: 50px !important;
    height: 40px;
    line-height: 40px;
  }
}
</style>
