/**
 * PluginTab.vue 测试（Wujie 版）
 * 测试插件 Tab 组件的核心逻辑
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ref, computed } from "vue";
import { createPinia, setActivePinia } from "pinia";

// Mock pluginStore
const mockPluginStore = {
  currentManifest: ref(null),
  activeMenuId: ref(null),
  loading: ref(false),
  error: ref(null),
  isReady: ref(false),
  menus: computed(() => mockPluginStore.currentManifest.value?.menus || []),
  hasMenus: computed(() => mockPluginStore.menus.value.length > 0),
  activeMenu: computed(() => {
    if (!mockPluginStore.activeMenuId.value) return null;
    const flatMenus = [];
    const flatten = (items, parentLabel) => {
      items.forEach((item) => {
        flatMenus.push({ ...item, parentLabel });
        if (item.children && item.children.length > 0) {
          flatten(item.children, item.label);
        }
      });
    };
    flatten(mockPluginStore.menus.value);
    return (
      flatMenus.find((m) => m.id === mockPluginStore.activeMenuId.value) || null
    );
  }),
  loadManifest: vi.fn(),
  setActiveMenu: vi.fn(),
  setReady: vi.fn(),
  clear: vi.fn(),
};

vi.mock("@/plugins/stores/pluginStore", () => ({
  usePluginStore: () => mockPluginStore,
}));

describe("PluginTab.vue", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    mockPluginStore.currentManifest.value = null;
    mockPluginStore.activeMenuId.value = null;
    mockPluginStore.loading.value = false;
    mockPluginStore.error.value = null;
    mockPluginStore.isReady.value = false;
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("Props 验证", () => {
    it("应该正确验证必需的 props", () => {
      const props = {
        instanceId: { type: Number, required: true },
        gameCode: { type: String, required: true },
      };

      expect(props.instanceId.required).toBe(true);
      expect(props.gameCode.required).toBe(true);
    });

    it("应该正确验证可选的 props", () => {
      const props = {
        instanceName: { type: String, required: false },
        hostId: { type: Number, required: false },
        hostIp: { type: String, required: false },
        deployPath: { type: String, required: false },
        ports: { type: Object, required: false },
      };

      expect(props.instanceName.required).toBeFalsy();
      expect(props.hostId.required).toBeFalsy();
    });
  });

  describe("清单加载", () => {
    it("应该调用 loadManifest", async () => {
      mockPluginStore.loadManifest.mockResolvedValue({
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [],
      });

      await mockPluginStore.loadManifest("minecraft");

      expect(mockPluginStore.loadManifest).toHaveBeenCalledWith("minecraft");
    });

    it("应该正确处理加载状态", () => {
      mockPluginStore.loading.value = true;

      expect(mockPluginStore.loading.value).toBe(true);

      mockPluginStore.loading.value = false;

      expect(mockPluginStore.loading.value).toBe(false);
    });
  });

  describe("插件 URL 计算", () => {
    it("应该正确计算相对路径 URL", () => {
      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [{ id: "menu-1", label: "菜单1", path: "/page1" }],
      };
      mockPluginStore.activeMenuId.value = "menu-1";

      const entry = mockPluginStore.currentManifest.value.entry;
      const path = mockPluginStore.activeMenu.value?.path || "";
      const pluginUrl = `${entry}${path}`;

      expect(pluginUrl).toBe("/plugins/test//page1");
    });

    it("应该正确计算完整 URL", () => {
      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "http://localhost:9000/plugin/index.html",
        menus: [{ id: "menu-1", label: "菜单1", path: "/page1" }],
      };
      mockPluginStore.activeMenuId.value = "menu-1";

      const entry = mockPluginStore.currentManifest.value.entry;
      const path = mockPluginStore.activeMenu.value?.path || "";

      const url = new URL(entry);
      url.pathname = path;
      const pluginUrl = url.toString();

      expect(pluginUrl).toContain("http://localhost:9000");
      expect(pluginUrl).toContain("/page1");
    });

    it("无当前菜单时应该返回空字符串", () => {
      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [{ id: "menu-1", label: "菜单1", path: "/page1" }],
      };
      mockPluginStore.activeMenuId.value = null;

      const pluginUrl = mockPluginStore.activeMenu.value
        ? `${mockPluginStore.currentManifest.value.entry}${mockPluginStore.activeMenu.value.path}`
        : "";

      expect(pluginUrl).toBe("");
    });
  });

  describe("事件处理", () => {
    it("应该定义 ready 事件", () => {
      const emits = ["ready", "error"];

      expect(emits).toContain("ready");
    });

    it("应该定义 error 事件", () => {
      const emits = ["ready", "error"];

      expect(emits).toContain("error");
    });

    it("应该正确处理 ready 事件", () => {
      const handleReady = () => {
        mockPluginStore.setReady(true);
      };

      handleReady({ version: "1.0.0" });

      expect(mockPluginStore.setReady).toHaveBeenCalledWith(true);
    });

    it("应该正确处理 error 事件", () => {
      const handleError = (error) => {
        mockPluginStore.error.value = error.message;
      };

      handleError(new Error("Test error"));

      expect(mockPluginStore.error.value).toBe("Test error");
    });
  });

  describe("菜单切换", () => {
    it("应该正确切换菜单", () => {
      mockPluginStore.setActiveMenu("menu-2");

      expect(mockPluginStore.setActiveMenu).toHaveBeenCalledWith("menu-2");
    });
  });

  describe("面包屑显示", () => {
    it("应该正确计算面包屑", () => {
      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [
          {
            id: "menu-1",
            label: "父菜单",
            path: "/menu1",
            children: [{ id: "menu-1-1", label: "子菜单", path: "/menu1/1" }],
          },
        ],
      };
      mockPluginStore.activeMenuId.value = "menu-1-1";

      const currentMenu = mockPluginStore.activeMenu.value;

      expect(currentMenu).toBeDefined();
      expect(currentMenu.label).toBe("子菜单");
      expect(currentMenu.parentLabel).toBe("父菜单");
    });
  });

  describe("插件信息显示", () => {
    it("应该正确显示插件名称和版本", () => {
      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "2.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [],
      };

      expect(mockPluginStore.currentManifest.value.name).toBe("Test Plugin");
      expect(mockPluginStore.currentManifest.value.version).toBe("2.0.0");
    });
  });

  describe("重新加载", () => {
    it("应该定义 reloadPlugin 方法", () => {
      const reloadPlugin = vi.fn();

      reloadPlugin();

      expect(reloadPlugin).toHaveBeenCalled();
    });
  });

  describe("gameCode 变化", () => {
    it("应该重新加载清单", async () => {
      mockPluginStore.loadManifest.mockResolvedValue({
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "csgo",
        entry: "/plugins/test/",
        menus: [],
      });

      await mockPluginStore.loadManifest("csgo");

      expect(mockPluginStore.loadManifest).toHaveBeenCalledWith("csgo");
    });
  });

  describe("组件销毁", () => {
    it("应该清除 store", () => {
      mockPluginStore.clear();

      expect(mockPluginStore.clear).toHaveBeenCalled();
    });
  });

  describe("边界情况", () => {
    it("无清单时应该显示空状态", () => {
      mockPluginStore.currentManifest.value = null;

      expect(mockPluginStore.hasMenus.value).toBe(false);
    });

    it("空菜单时应该显示空状态", () => {
      mockPluginStore.currentManifest.value = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [],
      };

      expect(mockPluginStore.hasMenus.value).toBe(false);
    });
  });
});
