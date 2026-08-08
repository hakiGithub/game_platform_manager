<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from "vue";
import { useRoute } from "vue-router";
import { useAppStore } from "@/stores/app";
import { useUserStore } from "@/stores/user";
import Sidebar from "./Sidebar.vue";
import Header from "./Header.vue";

const route = useRoute();
const appStore = useAppStore();
const userStore = useUserStore();

const sidebarWidth = computed(() => {
  return appStore.sidebarCollapsed
    ? "var(--sidebar-collapsed-width)"
    : "var(--sidebar-width)";
});

const mainStyle = computed(() => ({
  marginLeft: sidebarWidth.value,
  transition: "margin-left var(--transition-duration)",
}));

// 视口检测：设计规范要求最小支持 992px 宽度，低于此宽度展示不适配提示（ISSUE-003/004）
const isNarrowScreen = ref(false);
function checkViewport() {
  isNarrowScreen.value = window.innerWidth < 992;
}
onMounted(() => {
  checkViewport();
  window.addEventListener("resize", checkViewport);
});
onBeforeUnmount(() => {
  window.removeEventListener("resize", checkViewport);
});
</script>

<template>
  <!-- 不适配断点：展示全屏提示，避免在小屏下渲染挤压/不可用的布局 -->
  <div v-if="isNarrowScreen" class="narrow-screen-notice">
    <el-icon :size="64" class="notice-icon"><Monitor /></el-icon>
    <h2>建议使用更大屏幕</h2>
    <p>
      当前应用最小支持 992px 宽度的屏幕。请使用桌面端浏览器（建议 ≥1366×768）以获得完整功能体验。
    </p>
  </div>

  <el-container v-else class="main-layout">
    <!-- 侧边栏 -->
    <Sidebar :collapsed="appStore.sidebarCollapsed" />

    <!-- 主内容区 -->
    <el-container :style="mainStyle">
      <!-- 顶部导航 -->
      <Header />

      <!-- 内容区 -->
      <el-main class="main-content">
        <RouterView v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </RouterView>
      </el-main>
    </el-container>
  </el-container>
</template>

<style lang="scss" scoped>
.main-layout {
  width: 100%;
  height: 100vh;
  overflow: hidden;
}

:deep(.el-container) {
  flex-direction: column;
}

.main-content {
  background-color: var(--el-bg-color-page);
  padding: var(--spacing-lg);
  flex: 1;
  overflow-y: auto;
}

// 页面切换动画
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

// 不适配断点全屏提示（ISSUE-003/004）
.narrow-screen-notice {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 24px;
  background: var(--el-bg-color-page);
  color: var(--el-text-color-primary);

  .notice-icon {
    color: var(--el-color-primary);
    margin-bottom: 16px;
  }

  h2 {
    font-size: 20px;
    font-weight: 600;
    margin-bottom: 12px;
  }

  p {
    max-width: 320px;
    color: var(--el-text-color-secondary);
    line-height: 1.6;
    margin: 0;
  }
}
</style>
