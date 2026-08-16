import { defineStore } from "pinia";
import { ref, computed } from "vue";

export const useAppStore = defineStore("app", () => {
  // 侧边栏状态
  const sidebarCollapsed = ref(
    localStorage.getItem("sidebarCollapsed") === "true",
  );

  // 设备类型
  const device = ref("desktop");

  // 全局加载状态
  const loading = ref(false);

  // 主题设置
  const theme = ref(localStorage.getItem("theme") || "dark");
  if (typeof document !== "undefined") {
    document.documentElement.setAttribute("data-theme", theme.value);
  }

  // 计算属性
  const isMobile = computed(() => device.value === "mobile");

  // 切换侧边栏
  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value;
    localStorage.setItem("sidebarCollapsed", sidebarCollapsed.value);
  }

  // 设置侧边栏状态
  function setSidebarCollapsed(collapsed) {
    sidebarCollapsed.value = collapsed;
    localStorage.setItem("sidebarCollapsed", collapsed);
  }

  // 设置设备类型
  function setDevice(deviceType) {
    device.value = deviceType;
  }

  // 设置全局加载状态
  function setLoading(status) {
    loading.value = status;
  }

  // 切换主题
  function toggleTheme() {
    theme.value = theme.value === "light" ? "dark" : "light";
    localStorage.setItem("theme", theme.value);
    document.documentElement.setAttribute("data-theme", theme.value);
  }

  // 设置主题
  function setTheme(themeName) {
    theme.value = themeName;
    localStorage.setItem("theme", themeName);
    document.documentElement.setAttribute("data-theme", themeName);
  }

  return {
    sidebarCollapsed,
    device,
    loading,
    theme,
    isMobile,
    toggleSidebar,
    setSidebarCollapsed,
    setDevice,
    setLoading,
    toggleTheme,
    setTheme,
  };
});
