<script setup>
import { computed, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessageBox } from "element-plus";
import { useAppStore } from "@/stores/app";
import { useUserStore } from "@/stores/user";

const router = useRouter();
const route = useRoute();
const appStore = useAppStore();
const userStore = useUserStore();
const searchQuery = ref("");

const username = computed(() => userStore.username || "管理员");

// 面包屑：将当前页面标题从路径中提取出来，避免头部重复显示。
const breadcrumbs = computed(() =>
  route.matched.filter((r) => r.meta?.title && r.path !== "/").slice(0, -1)
);

const workspaceContext = computed(() => {
  const contextRoute = route.matched.find((record) =>
    ["Workspace", "Resources", "Services", "Extensions", "System"].includes(record.name)
  );
  return contextRoute?.meta?.title || "工作台";
});

const currentPageTitle = computed(() => route.meta?.title || workspaceContext.value);

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
  router.push("/system/configuration");
}

// 处理下拉菜单命令
function handleCommand(command) {
  switch (command) {
    case "profile":
      goToProfile();
      break;
    case "settings":
      router.push("/system/configuration");
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

function handleSearch() {
  const keyword = searchQuery.value.trim();
  if (!keyword) return;
  router.push({ path: "/services/instances/list", query: { keyword } });
}

function handleQuickAction(command) {
  router.push(command);
}
</script>

<template>
  <header class="header">
    <div class="header-left">
      <!-- 折叠按钮 -->
      <button class="collapse-btn" type="button" aria-label="折叠导航" @click="toggleSidebar">
        <el-icon :size="18">
        <Fold v-if="!appStore.sidebarCollapsed" />
        <Expand v-else />
        </el-icon>
      </button>

      <!-- 当前工作区与面包屑导航 -->
      <div class="header-context">
        <div class="header-context__topline">
          <span class="header-context__kicker">OPS CONSOLE / {{ workspaceContext }}</span>
          <span class="header-context__state"><i></i> READY</span>
        </div>
        <div class="header-context__main">
          <strong>{{ currentPageTitle }}</strong>
          <el-breadcrumb class="breadcrumbs" separator="/">
            <el-breadcrumb-item :to="{ path: '/workspace/overview' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item
              v-for="item in breadcrumbs"
              :key="item.path"
              :to="item.path"
            >
              {{ item.meta.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
      </div>
    </div>

    <div class="header-center">
      <el-input
        v-model="searchQuery"
        class="global-search"
        size="small"
        clearable
        placeholder="搜索资源、实例或动作..."
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
        <template #suffix><span class="shortcut-hint">⌘ K</span></template>
      </el-input>

      <el-dropdown class="quick-actions" trigger="click" @command="handleQuickAction">
        <el-button type="primary" plain>
          <el-icon><Plus /></el-icon>
          快速操作
          <el-icon class="quick-actions-arrow"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="/services/instances/deploy">
              <el-icon><CirclePlus /></el-icon>部署实例
            </el-dropdown-item>
            <el-dropdown-item command="/resources/hosts/list">
              <el-icon><Monitor /></el-icon>纳管主机
            </el-dropdown-item>
            <el-dropdown-item command="/services/tasks/list">
              <el-icon><List /></el-icon>查看任务
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div class="header-right">
      <div class="header-mode"><span></span><span>NIGHT OPS</span></div>

      <el-tooltip content="通知" placement="bottom">
        <button class="header-icon-button" type="button" aria-label="通知">
          <el-badge :value="3" :max="9" class="notification-badge">
            <el-icon :size="18"><Bell /></el-icon>
          </el-badge>
        </button>
      </el-tooltip>

      <el-tooltip content="帮助" placement="bottom">
        <button class="header-icon-button" type="button" aria-label="帮助">
          <el-icon :size="18"><QuestionFilled /></el-icon>
        </button>
      </el-tooltip>

      <el-tooltip content="全屏" placement="bottom">
        <button class="header-icon-button" type="button" aria-label="全屏" @click="toggleFullscreen">
          <el-icon :size="18"><FullScreen /></el-icon>
        </button>
      </el-tooltip>

      <!-- 用户信息 -->
      <el-dropdown trigger="click" @command="handleCommand">
        <div class="user-info">
          <el-avatar :size="32" icon="UserFilled" />
          <span class="user-copy">
            <span class="username">{{ username }}</span>
            <span class="user-role">OPERATIONS</span>
          </span>
          <el-icon><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">
              <el-icon><User /></el-icon>
              账号与安全
            </el-dropdown-item>
            <el-dropdown-item command="settings">
              <el-icon><Setting /></el-icon>
              系统配置
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
  gap: 14px;
  height: var(--header-height);
  padding: 0 16px;
  color: var(--el-text-color-primary);
  background: linear-gradient(180deg, #0d1e2b 0%, var(--platform-topbar) 100%);
  border-bottom: 1px solid var(--platform-line);
  position: sticky;
  top: 0;
  z-index: 1000;
}

.header-left {
  display: flex;
  align-items: center;
  flex: 0 1 390px;
  gap: var(--spacing-md);
  min-width: 310px;
}

.header-context {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  padding-left: 12px;
  border-left: 1px solid var(--platform-line);
}

.header-context__topline,
.header-context__main {
  display: flex;
  align-items: center;
  min-width: 0;
}

.header-context__topline {
  gap: 9px;
}

.header-context__kicker {
  overflow: hidden;
  color: var(--el-text-color-disabled);
  font-family: var(--el-font-family-mono);
  font-size: 8px;
  letter-spacing: 0.12em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-context__state {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  color: #6be39a;
  font-family: var(--el-font-family-mono);
  font-size: 8px;
  letter-spacing: 0.08em;
}

.header-context__state i {
  width: 5px;
  height: 5px;
  background: #51dc83;
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgba(81, 220, 131, 0.1);
}

.header-context__main {
  gap: 12px;
  margin-top: 4px;
}

.header-context__main strong {
  flex-shrink: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collapse-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  color: var(--el-text-color-secondary);
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition: color var(--transition-duration-fast);

  &:hover {
    color: var(--platform-cyan);
    background: var(--platform-bg-hover);
  }
}

.breadcrumbs {
  min-width: 0;
  overflow: hidden;

  :deep(.el-breadcrumb__inner),
  :deep(.el-breadcrumb__inner a) {
    color: var(--el-text-color-secondary);
    font-size: 10px;
    font-weight: var(--platform-font-weight-normal);
    white-space: nowrap;
  }

  :deep(.el-breadcrumb__item) {
    max-width: 110px;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  :deep(.el-breadcrumb__separator) {
    color: var(--el-text-color-disabled);
  }
}

.header-center {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  flex: 1;
  min-width: 250px;
}

.global-search {
  width: min(330px, 30vw);

  :deep(.el-input__wrapper) {
    min-height: 36px;
    background: rgba(6, 20, 30, 0.74);
    border: 1px solid rgba(75, 129, 149, 0.42);
    border-radius: 7px;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.025);

    &:hover,
    &.is-focus {
      border-color: rgba(24, 175, 230, 0.7);
      box-shadow: 0 0 0 2px rgba(24, 175, 230, 0.08);
    }
  }

  :deep(.el-input__prefix),
  :deep(.el-input__suffix) {
    color: var(--el-text-color-secondary);
  }
}

.shortcut-hint {
  color: var(--el-text-color-disabled);
  font-family: var(--el-font-family-mono);
  font-size: 11px;
}

.quick-actions {
  :deep(.el-button) {
    min-height: 36px;
    padding: 0 12px;
    color: #e8f7fb;
    background: linear-gradient(135deg, #168db9, #106b8e);
    border-color: #1aa6d7;
    border-radius: 7px;
    box-shadow: 0 4px 12px rgba(9, 114, 151, 0.2);

    &:hover {
      background: linear-gradient(135deg, #20a7d3, #14799f);
      border-color: #4ac2e6;
    }
  }
}

.quick-actions-arrow {
  margin-left: 6px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding-left: 10px;
  border-left: 1px solid var(--platform-line);
}

.header-mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: 3px;
  padding: 5px 8px;
  color: var(--el-text-color-disabled);
  font-family: var(--el-font-family-mono);
  font-size: 8px;
  letter-spacing: 0.08em;
  border: 1px solid rgba(91, 135, 154, 0.22);
  border-radius: 5px;
}

.header-mode span:first-child {
  width: 5px;
  height: 5px;
  background: var(--platform-cyan);
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgba(24, 175, 230, 0.1);
}

.header-icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  color: var(--el-text-color-secondary);
  background: transparent;
  border: 0;
  border-radius: 5px;
  cursor: pointer;
  transition: color var(--transition-duration-fast);

  &:hover {
    color: var(--platform-cyan);
    background: var(--platform-bg-hover);
  }
}

.notification-badge :deep(.el-badge__content) {
  top: 1px;
  right: 1px;
  border: 2px solid var(--platform-topbar);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 7px;
  border-radius: 7px;
  transition: background-color var(--transition-duration-fast);

  &:hover {
    background-color: var(--platform-bg-hover);
  }

}

.user-copy {
  display: flex;
  flex-direction: column;
  min-width: 58px;
}

.username {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role {
  margin-top: 2px;
  color: var(--el-text-color-disabled);
  font-family: var(--el-font-family-mono);
  font-size: 8px;
  letter-spacing: 0.08em;
}

:deep(.el-avatar) {
  color: var(--el-text-color-primary);
  background: var(--platform-surface-3);
}
</style>
