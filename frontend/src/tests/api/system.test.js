/**
 * system.js API 单元测试
 * 测试系统设置、操作日志、健康检查等接口
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// Mock request 模块
vi.mock("@/utils/request", () => ({
  default: vi.fn(),
}));

describe("system API", () => {
  let request;
  let systemApi;

  beforeEach(async () => {
    vi.clearAllMocks();
    request = (await import("@/utils/request")).default;
    systemApi = await import("@/api/system");
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("getSystemSettings - 获取系统设置", () => {
    it("应该正确获取系统设置", async () => {
      const mockResponse = {
        settings: [
          {
            configKey: "system.name",
            configValue: "游戏服务器管理平台",
            description: "系统名称",
          },
          {
            configKey: "system.logo",
            configValue: "/logo.png",
            description: "系统Logo",
          },
          {
            configKey: "security.maxLoginAttempts",
            configValue: "5",
            description: "最大登录尝试次数",
          },
          {
            configKey: "security.sessionTimeout",
            configValue: "30",
            description: "会话超时时间(分钟)",
          },
        ],
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.getSystemSettings();

      expect(request).toHaveBeenCalledWith({
        url: "/system/settings",
        method: "get",
      });
      expect(result.settings).toHaveLength(4);
      expect(result.settings[0].configKey).toBe("system.name");
    });

    it("应该处理空设置", async () => {
      const mockResponse = { settings: [] };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.getSystemSettings();

      expect(result.settings).toEqual([]);
    });

    it("应该处理获取失败", async () => {
      request.mockRejectedValue(new Error("网络错误"));

      await expect(systemApi.getSystemSettings()).rejects.toThrow("网络错误");
    });
  });

  describe("updateSystemSettings - 更新系统设置", () => {
    it("应该正确更新系统设置", async () => {
      const updateData = {
        settings: [
          { configKey: "system.name", configValue: "新的系统名称" },
          { configKey: "security.maxLoginAttempts", configValue: "3" },
        ],
      };
      request.mockResolvedValue(null);

      const result = await systemApi.updateSystemSettings(updateData);

      expect(request).toHaveBeenCalledWith({
        url: "/system/settings",
        method: "put",
        data: updateData,
      });
      expect(result).toBeNull();
    });

    it("应该更新单个设置项", async () => {
      const updateData = {
        settings: [{ configKey: "system.name", configValue: "测试平台" }],
      };
      request.mockResolvedValue(null);

      await systemApi.updateSystemSettings(updateData);

      expect(request).toHaveBeenCalledWith(
        expect.objectContaining({
          data: {
            settings: [{ configKey: "system.name", configValue: "测试平台" }],
          },
        }),
      );
    });

    it("应该处理无效的设置键", async () => {
      const updateData = {
        settings: [{ configKey: "invalid.key", configValue: "value" }],
      };
      request.mockRejectedValue(new Error("无效的配置键"));

      await expect(systemApi.updateSystemSettings(updateData)).rejects.toThrow(
        "无效的配置键",
      );
    });

    it("应该处理设置值格式错误", async () => {
      const updateData = {
        settings: [
          { configKey: "security.maxLoginAttempts", configValue: "invalid" },
        ],
      };
      request.mockRejectedValue(new Error("设置值格式错误"));

      await expect(systemApi.updateSystemSettings(updateData)).rejects.toThrow(
        "设置值格式错误",
      );
    });
  });

