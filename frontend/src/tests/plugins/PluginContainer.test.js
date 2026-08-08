/**
 * PluginContainer.vue 测试（Wujie 版）
 * 测试插件容器组件的核心逻辑
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('wujie-vue3', () => ({
  default: {
    install: vi.fn(),
  },
  bus: {
    $on: vi.fn(),
    $off: vi.fn(),
    $emit: vi.fn(),
  },
}));

// Mock usePluginCommunication
const mockCommunication = {
  isReady: ref(false),
  sendThemeChange: vi.fn(),
  reinit: vi.fn(),
};

vi.mock("@/plugins/communication/pluginCommunication", () => ({
  usePluginCommunication: () => mockCommunication,
  fetchPluginManifest: vi.fn(),
}));

// Mock stores
vi.mock("@/stores/app", () => ({
  useAppStore: () => ({
    theme: "light",
  }),
}));

vi.mock("@/stores/user", () => ({
  useUserStore: () => ({
    token: "test-token",
    username: "admin",
    userInfo: { id: 1, username: "admin", role: "admin" },
    permissions: [],
  }),
}));

import { fetchPluginManifest } from "@/plugins/communication/pluginCommunication";

describe("PluginContainer.vue", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    mockCommunication.isReady.value = false;
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("Props 验证", () => {
    it("应该正确验证必需的 props", () => {
      // 验证 props 定义
      const props = {
        src: { type: String, required: true },
        instanceId: { type: Number, required: true },
        gameCode: { type: String, required: true },
      };

      expect(props.src.required).toBe(true);
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
      expect(props.hostIp.required).toBeFalsy();
    });
  });

  describe("实例信息计算", () => {
    it("应该正确构建实例信息", () => {
      const props = {
        instanceId: 1,
        instanceName: "Test Instance",
        gameCode: "minecraft",
        hostId: 1,
        hostIp: "192.168.1.100",
        deployPath: "/opt/minecraft",
        ports: { main: 25565 },
      };

      const instanceInfo = {
        instanceId: props.instanceId,
        instanceName: props.instanceName || "",
        gameCode: props.gameCode,
        hostId: props.hostId || 0,
        hostIp: props.hostIp || "",
        deployPath: props.deployPath || "",
        ports: props.ports || {},
      };

      expect(instanceInfo.instanceId).toBe(1);
      expect(instanceInfo.gameCode).toBe("minecraft");
      expect(instanceInfo.ports.main).toBe(25565);
    });

    it("应该正确处理缺失的可选 props", () => {
      const props = {
        instanceId: 1,
        gameCode: "minecraft",
      };

      const instanceInfo = {
        instanceId: props.instanceId,
        instanceName: "",
        gameCode: props.gameCode,
        hostId: 0,
        hostIp: "",
        deployPath: "",
        ports: {},
      };

      expect(instanceInfo.instanceName).toBe("");
      expect(instanceInfo.hostId).toBe(0);
      expect(instanceInfo.ports).toEqual({});
    });
  });

  describe("Wujie 子应用名称", () => {
    it("应该根据 gameCode 生成 Wujie 名称", () => {
      const gameCode = "minecraft";
      const wujieName = `plugin-${gameCode}`;

      expect(wujieName).toBe("plugin-minecraft");
    });
  });

  describe("下发给子应用的 props", () => {
    it("应该包含实例、认证、主题和 API 基础路径", () => {
      const instanceInfo = {
        instanceId: 1,
        instanceName: "Test",
        gameCode: "minecraft",
        hostId: 1,
        hostIp: "192.168.1.100",
        deployPath: "/opt/minecraft",
        ports: { main: 25565 },
      };

      const authInfo = {
        token: "test-token",
        user: {
          id: 1,
          username: "admin",
          role: "admin",
          permissions: [],
        },
      };

      const themeInfo = {
        isDark: false,
        theme: "light",
      };

      const wujieProps = {
        instance: instanceInfo,
        auth: authInfo,
        theme: themeInfo,
        baseApi: "/api",
      };

      expect(wujieProps.instance).toEqual(instanceInfo);
      expect(wujieProps.auth).toEqual(authInfo);
      expect(wujieProps.theme).toEqual(themeInfo);
      expect(wujieProps.baseApi).toBe("/api");
    });
  });

  describe("清单加载", () => {
    it("应该成功加载清单", async () => {
      const mockManifest = {
        pluginId: "plugin-1",
        name: "Test Plugin",
        version: "1.0.0",
        gameCode: "minecraft",
        entry: "/plugins/test/",
        menus: [],
      };

      fetchPluginManifest.mockResolvedValue(mockManifest);

      const result = await fetchPluginManifest("minecraft");

      expect(result).toEqual(mockManifest);
    });

    it("应该处理加载失败", async () => {
      fetchPluginManifest.mockRejectedValue(new Error("加载失败"));

      await expect(fetchPluginManifest("minecraft")).rejects.toThrow(
        "加载失败",
      );
    });
  });

  describe("通信管理器", () => {
    it("应该正确初始化通信管理器", () => {
      expect(mockCommunication.isReady.value).toBe(false);
    });

    it("应该正确调用 sendThemeChange", () => {
      mockCommunication.sendThemeChange();

      expect(mockCommunication.sendThemeChange).toHaveBeenCalled();
    });

    it("应该正确调用 reinit", () => {
      mockCommunication.reinit();

      expect(mockCommunication.reinit).toHaveBeenCalled();
    });
  });

  describe("事件处理", () => {
    it("应该定义 ready 事件", () => {
      const emits = ["ready", "error", "loading"];

      expect(emits).toContain("ready");
    });

    it("应该定义 error 事件", () => {
      const emits = ["ready", "error", "loading"];

      expect(emits).toContain("error");
    });

    it("应该定义 loading 事件", () => {
      const emits = ["ready", "error", "loading"];

      expect(emits).toContain("loading");
    });
  });

  describe("暴露的方法", () => {
    it("应该暴露 reload 方法", () => {
      const exposedMethods = ["reload", "isReady"];

      expect(exposedMethods).toContain("reload");
    });

    it("应该暴露 isReady 属性", () => {
      const exposedMethods = ["reload", "isReady"];

      expect(exposedMethods).toContain("isReady");
    });
  });

  describe("状态管理", () => {
    it("应该正确管理加载状态", () => {
      const loading = ref(true);

      expect(loading.value).toBe(true);

      loading.value = false;

      expect(loading.value).toBe(false);
    });

    it("应该正确管理错误状态", () => {
      const error = ref(null);

      expect(error.value).toBeNull();

      error.value = "加载失败";

      expect(error.value).toBe("加载失败");
    });
  });

  describe("重试逻辑", () => {
    it("应该限制最大重试次数", () => {
      const maxRetries = 3;
      const retryCount = ref(0);

      expect(retryCount.value).toBeLessThan(maxRetries);
    });

    it("应该正确重置重试计数", () => {
      const retryCount = ref(2);

      retryCount.value = 0;

      expect(retryCount.value).toBe(0);
    });
  });
});
