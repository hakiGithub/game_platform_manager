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

  describe("getOperationLogs - 获取操作日志", () => {
    it("应该使用默认参数获取操作日志", async () => {
      const mockResponse = {
        records: [
          {
            id: 1,
            operatorName: "admin",
            operationModule: "主机管理",
            operationType: "CREATE",
            operationDesc: "创建主机: 主机1",
            requestMethod: "POST",
            requestUrl: "/api/hosts",
            responseStatus: 1,
            operationTime: "2024-01-01T12:00:00Z",
            operationIp: "192.168.1.100",
          },
        ],
        current: 1,
        size: 10,
        total: 1,
        pages: 1,
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.getOperationLogs();

      expect(request).toHaveBeenCalledWith({
        url: "/system/logs",
        method: "get",
        params: undefined,
      });
      expect(result.records).toHaveLength(1);
      expect(result.records[0].operatorName).toBe("admin");
    });

    it("应该使用筛选参数获取操作日志", async () => {
      const params = {
        current: 1,
        size: 20,
        operatorName: "admin",
        operationModule: "实例管理",
        operationType: "UPDATE",
        responseStatus: 1,
        startTime: "2024-01-01T00:00:00Z",
        endTime: "2024-01-31T23:59:59Z",
      };
      const mockResponse = {
        records: [
          {
            id: 1,
            operatorName: "admin",
            operationModule: "实例管理",
            operationType: "UPDATE",
            responseStatus: 1,
          },
        ],
        current: 1,
        size: 20,
        total: 1,
        pages: 1,
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.getOperationLogs(params);

      expect(request).toHaveBeenCalledWith({
        url: "/system/logs",
        method: "get",
        params,
      });
      expect(result.records[0].operationType).toBe("UPDATE");
    });

    it("应该处理空日志列表", async () => {
      const mockResponse = {
        records: [],
        current: 1,
        size: 10,
        total: 0,
        pages: 0,
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.getOperationLogs();

      expect(result.records).toEqual([]);
      expect(result.total).toBe(0);
    });

    it("应该处理时间范围筛选", async () => {
      const params = {
        startTime: "2024-01-01T00:00:00Z",
        endTime: "2024-01-02T00:00:00Z",
      };
      request.mockResolvedValue({ records: [], total: 0 });

      await systemApi.getOperationLogs(params);

      expect(request).toHaveBeenCalledWith(
        expect.objectContaining({
          params: expect.objectContaining({
            startTime: "2024-01-01T00:00:00Z",
            endTime: "2024-01-02T00:00:00Z",
          }),
        }),
      );
    });
  });

  describe("healthCheck - 健康检查", () => {
    it("应该正确获取健康状态", async () => {
      const mockResponse = {
        status: "UP",
        timestamp: "2024-01-01T12:00:00Z",
        version: "1.0.0",
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.healthCheck();

      expect(request).toHaveBeenCalledWith({
        url: "/system/health",
        method: "get",
      });
      expect(result.status).toBe("UP");
      expect(result.version).toBe("1.0.0");
    });

    it("应该处理服务不可用", async () => {
      const mockResponse = {
        status: "DOWN",
        timestamp: "2024-01-01T12:00:00Z",
        version: "1.0.0",
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.healthCheck();

      expect(result.status).toBe("DOWN");
    });

    it("应该处理网络错误", async () => {
      request.mockRejectedValue(new Error("Network Error"));

      await expect(systemApi.healthCheck()).rejects.toThrow("Network Error");
    });
  });

  describe("getSystemInfo - 获取系统信息", () => {
    it("应该正确获取系统信息", async () => {
      const mockResponse = {
        name: "游戏服务器管理平台",
        version: "1.0.0",
        description: "一个用于管理游戏服务器的平台",
        javaVersion: "17.0.8",
        osName: "Linux",
        osVersion: "5.15.0",
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.getSystemInfo();

      expect(request).toHaveBeenCalledWith({
        url: "/system/info",
        method: "get",
      });
      expect(result.name).toBe("游戏服务器管理平台");
      expect(result.javaVersion).toBe("17.0.8");
    });

    it("应该处理获取失败", async () => {
      request.mockRejectedValue(new Error("网络错误"));

      await expect(systemApi.getSystemInfo()).rejects.toThrow("网络错误");
    });
  });

  describe("getStatistics - 获取系统统计信息", () => {
    it("应该正确获取统计信息", async () => {
      const mockResponse = {
        hosts: {
          total: 10,
          online: 8,
          offline: 2,
        },
        instances: {
          total: 25,
          running: 15,
          stopped: 8,
          error: 2,
        },
        plugins: {
          total: 50,
          enabled: 40,
          disabled: 10,
        },
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.getStatistics();

      expect(request).toHaveBeenCalledWith({
        url: "/system/statistics",
        method: "get",
      });
      expect(result.hosts.total).toBe(10);
      expect(result.instances.running).toBe(15);
      expect(result.plugins.enabled).toBe(40);
    });

    it("应该处理部分统计数据缺失", async () => {
      const mockResponse = {
        hosts: { total: 5, online: 3, offline: 2 },
        // instances 和 plugins 缺失
      };
      request.mockResolvedValue(mockResponse);

      const result = await systemApi.getStatistics();

      expect(result.hosts.total).toBe(5);
      expect(result.instances).toBeUndefined();
    });

    it("应该处理获取失败", async () => {
      request.mockRejectedValue(new Error("网络错误"));

      await expect(systemApi.getStatistics()).rejects.toThrow("网络错误");
    });
  });
});
