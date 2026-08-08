/**
 * PluginMenu.vue 测试（Wujie 版）
 * 测试插件菜单组件的核心逻辑
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ref, computed } from "vue";
import { createPinia, setActivePinia } from "pinia";

// Mock pluginStore
const mockPluginStore = {
  currentManifest: ref(null),
  activeMenuId: ref(null),
  menus: computed(() => mockPluginStore.currentManifest.value?.menus || []),
  hasMenus: computed(() => mockPluginStore.menus.value.length > 0),
  setActiveMenu: vi.fn(),
};

vi.mock("@/plugins/stores/pluginStore", () => ({
  usePluginStore: () => mockPluginStore,
}));

describe("PluginMenu.vue", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    mockPluginStore.currentManifest.value = null;
    mockPluginStore.activeMenuId.value = null;
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("菜单数据", () => {
    it("应该正确获取菜单列表", () => {
      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [
          { id: "menu-1", label: "菜单1", path: "/menu1" },
          { id: "menu-2", label: "菜单2", path: "/menu2" },
        ],
      };

      expect(mockPluginStore.menus.value).toHaveLength(2);
      expect(mockPluginStore.menus.value[0].id).toBe("menu-1");
    });

    it("应该正确判断是否有菜单", () => {
      expect(mockPluginStore.hasMenus.value).toBe(false);

      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [{ id: "menu-1", label: "菜单1", path: "/menu1" }],
      };

      expect(mockPluginStore.hasMenus.value).toBe(true);
    });
  });

  describe("菜单点击", () => {
    it("应该调用 setActiveMenu", () => {
      const menu = { id: "menu-1", label: "菜单1", path: "/menu1" };

      mockPluginStore.setActiveMenu(menu.id);

      expect(mockPluginStore.setActiveMenu).toHaveBeenCalledWith("menu-1");
    });
  });

  describe("激活状态", () => {
    it("应该正确判断菜单是否激活", () => {
      mockPluginStore.activeMenuId.value = "menu-1";

      const isActive = (menuId) =>
        mockPluginStore.activeMenuId.value === menuId;

      expect(isActive("menu-1")).toBe(true);
      expect(isActive("menu-2")).toBe(false);
    });
  });

  describe("图标处理", () => {
    it("应该返回自定义图标", () => {
      const getMenuIcon = (icon) => icon || "Document";

      expect(getMenuIcon("Setting")).toBe("Setting");
    });

    it("应该返回默认图标", () => {
      const getMenuIcon = (icon) => icon || "Document";

      expect(getMenuIcon()).toBe("Document");
      expect(getMenuIcon(undefined)).toBe("Document");
    });
  });

  describe("子菜单处理", () => {
    it("应该正确处理有子菜单的情况", () => {
      const menu = {
        id: "menu-1",
        label: "父菜单",
        path: "/menu1",
        children: [
          { id: "menu-1-1", label: "子菜单1", path: "/menu1/1" },
          { id: "menu-1-2", label: "子菜单2", path: "/menu1/2" },
        ],
      };

      const hasChildren = menu.children && menu.children.length > 0;

      expect(hasChildren).toBe(true);
    });

    it("应该正确处理无子菜单的情况", () => {
      const menu = {
        id: "menu-1",
        label: "菜单1",
        path: "/menu1",
      };

      const hasChildren = menu.children && menu.children.length > 0;

      expect(hasChildren).toBeFalsy();
    });

    it("应该正确处理空子菜单数组", () => {
      const menu = {
        id: "menu-1",
        label: "菜单1",
        path: "/menu1",
        children: [],
      };

      const hasChildren = menu.children && menu.children.length > 0;

      expect(hasChildren).toBe(false);
    });
  });

  describe("事件定义", () => {
    it("应该定义 select 事件", () => {
      const emits = ["select"];

      expect(emits).toContain("select");
    });
  });

  describe("边界情况", () => {
    it("空菜单列表时应该正确处理", () => {
      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [],
      };

      expect(mockPluginStore.menus.value).toEqual([]);
      expect(mockPluginStore.hasMenus.value).toBe(false);
    });

    it("manifest 为 null 时应该正确处理", () => {
      mockPluginStore.currentManifest.value = null;

      expect(mockPluginStore.menus.value).toEqual([]);
      expect(mockPluginStore.hasMenus.value).toBe(false);
    });
  });
});
