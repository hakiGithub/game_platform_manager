<script setup>
import { computed } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessageBox } from "element-plus";
import { useAppStore } from "@/stores/app";
import { useUserStore } from "@/stores/user";

const router = useRouter();
const route = useRoute();
const appStore = useAppStore();
const userStore = useUserStore();

const username = computed(() => userStore.username || "管理员");

// 面包屑：排除根路由和无标题的匹配项
const breadcrumbs = computed(() =>
  route.matched.filter((r) => r.meta?.title && r.path !== "/")
);

// 切换侧边栏
function toggleSidebar() {
  appStore.toggleSidebar();
}

// 退出登录
function handleLogout() {
  ElMessageBox.confirm("确定要退出登录吗？", "提示", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    type: "warning",
  })
    .then(() => {
      userStore.logout();
    })
    .catch(() => {});
}

// 跳转个人中心
function goToProfile() {
  router.push("/profile");
}

// 处理下拉菜单命令
function handleCommand(command) {
  switch (command) {
    case "profile":
      goToProfile();
      break;
    case "settings":
      router.push("/system/settings");
      break;
    case "logout":
      handleLogout();
      break;
  }
}

function toggleFullscreen() {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen();
  } else {
    document.exitFullscreen();
  }
}
</script>

<template>
  <header class="header">
    <div class="header-left">
      <!-- 折叠按钮 -->
      <el-icon class="collapse-btn" :size="20" @click="toggleSidebar">
        <Fold v-if="!appStore.sidebarCollapsed" />
        <Expand v-else />
      </el-icon>

      <!-- 面包屑导航 -->
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item
          v-for="(item, index) in breadcrumbs"
          :key="item.path"
          :to="index < breadcrumbs.length - 1 ? item.path : undefined"
        >
          {{ item.meta.title }}
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <div class="header-right">
      <!-- 全屏按钮 -->
      <el-tooltip content="全屏" placement="bottom">
        <el-icon class="header-icon" :size="18" @click="toggleFullscreen">
          <FullScreen />
        </el-icon>
      </el-tooltip>

      <!-- 用户信息 -->
      <el-dropdown trigger="click" @command="handleCommand">
        <div class="user-info">
          <el-avatar :size="32" icon="UserFilled" />
          <span class="username">{{ username }}</span>
          <el-icon><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">
              <el-icon><User /></el-icon>
              个人中心
            </el-dropdown-item>
            <el-dropdown-item command="settings">
              <el-icon><Setting /></el-icon>
              系统设置
            </el-dropdown-item>
            <el-dropdown-item divided command="logout">
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style lang="scss" scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
  padding: 0 var(--spacing-lg);
  background-color: var(--el-bg-color);
  box-shadow: var(--box-shadow-base);
  position: sticky;
  top: 0;
  z-index: 1000;
}

.header-left {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.collapse-btn {
  cursor: pointer;
  color: var(--el-text-color-regular);
  transition: color var(--transition-duration-fast);

  &:hover {
    color: var(--el-color-primary);
  }
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
}

.header-icon {
  cursor: pointer;
  color: var(--el-text-color-regular);
  transition: color var(--transition-duration-fast);

  &:hover {
    color: var(--el-color-primary);
  }
}

.user-info {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  cursor: pointer;
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--el-border-radius-base);
  transition: background-color var(--transition-duration-fast);

  &:hover {
    background-color: var(--el-bg-color-page);
  }

  .username {
    font-size: var(--el-font-size-base);
    color: var(--el-text-color-primary);
  }
}
</style>
