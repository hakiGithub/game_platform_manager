/**
 * instance.js API 单元测试
 * 测试实例列表、创建、启动/停止/重启、文件管理等接口
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// Mock request 模块
vi.mock("@/utils/request", () => ({
  default: vi.fn(),
}));

describe("instance API", () => {
  let request;
  let instanceApi;

  beforeEach(async () => {
    vi.clearAllMocks();
    request = (await import("@/utils/request")).default;
    instanceApi = await import("@/api/instance");
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("getInstanceList - 获取实例列表", () => {
    it("应该使用默认参数获取实例列表", async () => {
      const mockResponse = {
        records: [
          { id: 1, name: "实例1", gameType: "minecraft", status: 1, hostId: 1 },
          { id: 2, name: "实例2", gameType: "terraria", status: 0, hostId: 1 },
        ],
        current: 1,
        size: 10,
        total: 2,
        pages: 1,
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.getInstanceList();

      expect(request).toHaveBeenCalledWith({
        url: "/instances",
        method: "get",
        params: undefined,
      });
      expect(result).toEqual(mockResponse);
    });

    it("应该使用筛选参数获取实例列表", async () => {
      const params = {
        current: 1,
        size: 20,
        name: "我的世界",
        hostId: 1,
        gameCode: "minecraft",
        status: 1,
      };
      const mockResponse = {
        records: [
          { id: 1, name: "我的世界服务器", gameType: "minecraft", status: 1 },
        ],
        current: 1,
        size: 20,
        total: 1,
        pages: 1,
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.getInstanceList(params);

      expect(request).toHaveBeenCalledWith({
        url: "/instances",
        method: "get",
        params,
      });
      expect(result.records[0].gameType).toBe("minecraft");
    });
  });

  describe("getInstancesByGameId - 根据游戏ID获取实例列表", () => {
    it("应该正确根据游戏ID获取实例列表", async () => {
      const mockResponse = [
        {
          id: 1,
          instanceName: "Minecraft-1",
          gameId: 1,
          hostId: 1,
          status: "running",
        },
        {
          id: 2,
          instanceName: "Minecraft-2",
          gameId: 1,
          hostId: 2,
          status: "stopped",
        },
      ];
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.getInstancesByGameId(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/game/1",
        method: "get",
      });
      expect(result).toHaveLength(2);
      expect(result[0].instanceName).toBe("Minecraft-1");
    });

    it("应该处理游戏无实例的情况", async () => {
      request.mockResolvedValue([]);

      const result = await instanceApi.getInstancesByGameId(999);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/game/999",
        method: "get",
      });
      expect(result).toEqual([]);
    });

    it("应该处理获取失败", async () => {
      request.mockRejectedValue(new Error("游戏不存在"));

      await expect(instanceApi.getInstancesByGameId(999)).rejects.toThrow(
        "游戏不存在",
      );
    });
  });

  describe("getInstanceDetail - 获取实例详情", () => {
    it("应该正确获取实例详情", async () => {
      const mockResponse = {
        id: 1,
        name: "我的世界服务器",
        gameType: "minecraft",
        gameVersion: "1.20.1",
        hostId: 1,
        deployPath: "/opt/minecraft/server1",
        port: 25565,
        startArgs: "-Xmx4G -Xms4G",
        autoRestart: 1,
        status: 1,
        processId: 12345,
        remark: "主服务器",
        createdAt: "2024-01-01T00:00:00Z",
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.getInstanceDetail(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1",
        method: "get",
      });
      expect(result.port).toBe(25565);
      expect(result.autoRestart).toBe(1);
    });

    it("应该处理实例不存在", async () => {
      request.mockRejectedValue(new Error("实例不存在"));

      await expect(instanceApi.getInstanceDetail(999)).rejects.toThrow(
        "实例不存在",
      );
    });
  });

  describe("createInstance - 创建实例", () => {
    it("应该使用正确的参数创建实例", async () => {
      const instanceData = {
        name: "新实例",
        gameType: "minecraft",
        gameVersion: "1.20.1",
        hostId: 1,
        deployPath: "/opt/minecraft/newserver",
        port: 25566,
        startArgs: "-Xmx2G -Xms2G",
        autoRestart: 0,
        remark: "测试实例",
      };
      const mockResponse = {
        id: 3,
        deployTaskId: "task-12345",
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.createInstance(instanceData);

      expect(request).toHaveBeenCalledWith({
        url: "/instances",
        method: "post",
        data: instanceData,
      });
      expect(result.id).toBe(3);
      expect(result.deployTaskId).toBe("task-12345");
    });

    it("应该处理端口冲突", async () => {
      const instanceData = {
        name: "冲突实例",
        gameType: "minecraft",
        hostId: 1,
        port: 25565,
      };
      request.mockRejectedValue(new Error("端口 25565 已被占用"));

      await expect(instanceApi.createInstance(instanceData)).rejects.toThrow(
        "端口 25565 已被占用",
      );
    });

    it("应该处理主机不存在", async () => {
      const instanceData = {
        name: "测试实例",
        gameType: "minecraft",
        hostId: 999,
      };
      request.mockRejectedValue(new Error("主机不存在"));

      await expect(instanceApi.createInstance(instanceData)).rejects.toThrow(
        "主机不存在",
      );
    });
  });

  describe("updateInstance - 更新实例", () => {
    it("应该正确更新实例配置", async () => {
      const updateData = {
        name: "更新后的名称",
        port: 25567,
        startArgs: "-Xmx8G -Xms8G",
        autoRestart: 1,
      };
      request.mockResolvedValue(null);

      const result = await instanceApi.updateInstance(1, updateData);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1",
        method: "put",
        data: updateData,
      });
      expect(result).toBeNull();
    });
  });

  describe("deleteInstance - 删除实例", () => {
    it("应该正确删除实例（不删除文件）", async () => {
      request.mockResolvedValue(null);

      const result = await instanceApi.deleteInstance(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1",
        method: "delete",
        params: undefined,
      });
      expect(result).toBeNull();
    });

    it("应该删除实例并删除文件", async () => {
      request.mockResolvedValue(null);

      await instanceApi.deleteInstance(1, { deleteFiles: true });

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1",
        method: "delete",
        params: { deleteFiles: true },
      });
    });

    it("应该处理删除运行中的实例", async () => {
      request.mockRejectedValue(new Error("实例正在运行，无法删除"));

      await expect(instanceApi.deleteInstance(1)).rejects.toThrow(
        "实例正在运行，无法删除",
      );
    });
  });

  describe("startInstance - 启动实例", () => {
    it("应该成功启动实例", async () => {
      const mockResponse = {
        success: true,
        processId: 12345,
        message: "实例启动成功",
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.startInstance(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/start",
        method: "post",
      });
      expect(result.success).toBe(true);
      expect(result.processId).toBe(12345);
    });

    it("应该处理启动失败", async () => {
      const mockResponse = {
        success: false,
        message: "端口被占用",
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.startInstance(1);

      expect(result.success).toBe(false);
      expect(result.message).toBe("端口被占用");
    });

    it("应该处理实例已在运行", async () => {
      request.mockRejectedValue(new Error("实例已在运行中"));

      await expect(instanceApi.startInstance(1)).rejects.toThrow(
        "实例已在运行中",
      );
    });
  });

  describe("stopInstance - 停止实例", () => {
    it("应该正常停止实例", async () => {
      const mockResponse = {
        success: true,
        message: "实例停止成功",
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.stopInstance(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/stop",
        method: "post",
        data: undefined,
      });
      expect(result.success).toBe(true);
    });

    it("应该强制停止实例", async () => {
      request.mockResolvedValue({ success: true, message: "实例已强制停止" });

      await instanceApi.stopInstance(1, { force: true });

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/stop",
        method: "post",
        data: { force: true },
      });
    });

    it("应该处理实例未运行", async () => {
      request.mockRejectedValue(new Error("实例未在运行"));

      await expect(instanceApi.stopInstance(1)).rejects.toThrow("实例未在运行");
    });
  });

  describe("restartInstance - 重启实例", () => {
    it("应该成功重启实例", async () => {
      const mockResponse = {
        success: true,
        processId: 54321,
        message: "实例重启成功",
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.restartInstance(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/restart",
        method: "post",
      });
      expect(result.processId).toBe(54321);
    });
  });

  describe("getInstanceStatus - 获取实例状态", () => {
    it("应该正确获取实例状态", async () => {
      const mockResponse = {
        status: 1,
        processId: 12345,
        uptime: 3600,
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.getInstanceStatus(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/status",
        method: "get",
      });
      expect(result.uptime).toBe(3600);
    });
  });

  describe("getInstanceLogs - 获取实例日志", () => {
    it("应该获取默认行数的日志", async () => {
      const mockResponse = {
        logs: ["log1", "log2", "log3"],
        total: 3,
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.getInstanceLogs(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/logs",
        method: "get",
        params: undefined,
      });
      expect(result.logs).toHaveLength(3);
    });

    it("应该使用查询参数获取日志", async () => {
      const params = {
        lines: 50,
        keyword: "error",
        startTime: "2024-01-01T00:00:00Z",
        endTime: "2024-01-02T00:00:00Z",
      };
      request.mockResolvedValue({ logs: ["error log"], total: 1 });

      await instanceApi.getInstanceLogs(1, params);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/logs",
        method: "get",
        params,
      });
    });
  });

  describe("getInstanceConfig - 获取实例配置", () => {
    it("应该获取默认配置文件", async () => {
      const mockResponse = {
        fileName: "server.properties",
        content: "server-port=25565\nmax-players=20",
        lastModified: "2024-01-01T00:00:00Z",
        size: 1024,
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.getInstanceConfig(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/config",
        method: "get",
        params: undefined,
      });
      expect(result.fileName).toBe("server.properties");
    });

    it("应该获取指定配置文件", async () => {
      request.mockResolvedValue({
        fileName: "bukkit.yml",
        content: "settings:\n  allow-end: true",
        size: 512,
      });

      await instanceApi.getInstanceConfig(1, { configFile: "bukkit.yml" });

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/config",
        method: "get",
        params: { configFile: "bukkit.yml" },
      });
    });
  });

  describe("updateInstanceConfig - 更新实例配置", () => {
    it("应该更新配置（不重启）", async () => {
      const configData = {
        configFile: "server.properties",
        content: "server-port=25566\nmax-players=30",
        restart: false,
      };
      request.mockResolvedValue(null);

      const result = await instanceApi.updateInstanceConfig(1, configData);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/config",
        method: "put",
        data: configData,
      });
      expect(result).toBeNull();
    });

    it("应该更新配置并重启实例", async () => {
      const configData = {
        content: "max-players=50",
        restart: true,
      };
      request.mockResolvedValue(null);

      await instanceApi.updateInstanceConfig(1, configData);

      expect(request).toHaveBeenCalledWith(
        expect.objectContaining({
          data: expect.objectContaining({ restart: true }),
        }),
      );
    });
  });

  describe("getInstanceFiles - 获取实例文件列表", () => {
    it("应该获取根目录文件列表", async () => {
      const mockResponse = {
        currentPath: "/",
        files: [
          { name: "server.properties", type: "file", size: 1024 },
          { name: "plugins", type: "directory" },
          { name: "world", type: "directory" },
        ],
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.getInstanceFiles(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/files",
        method: "get",
        params: undefined,
      });
      expect(result.files).toHaveLength(3);
    });

    it("应该获取指定目录文件列表", async () => {
      request.mockResolvedValue({
        currentPath: "/plugins",
        files: [{ name: "Essentials.jar", type: "file", size: 2048 }],
      });

      await instanceApi.getInstanceFiles(1, {
        path: "/plugins",
        showHidden: false,
      });

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/files",
        method: "get",
        params: { path: "/plugins", showHidden: false },
      });
    });
  });

  describe("downloadFile - 下载文件", () => {
    it("应该正确下载文件", async () => {
      const blob = new Blob(["file content"], {
        type: "application/octet-stream",
      });
      request.mockResolvedValue(blob);

      const result = await instanceApi.downloadFile(1, "/server.properties");

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/files/download",
        method: "get",
        params: { path: "/server.properties" },
        responseType: "blob",
      });
      expect(result).toBe(blob);
    });
  });

  describe("uploadFile - 上传文件", () => {
    it("应该正确上传文件", async () => {
      const formData = new FormData();
      formData.append("file", new File(["content"], "plugin.jar"));
      formData.append("path", "/plugins");
      formData.append("overwrite", "true");

      const mockResponse = {
        fileName: "plugin.jar",
        filePath: "/plugins/plugin.jar",
        size: 1024,
      };
      request.mockResolvedValue(mockResponse);

      const result = await instanceApi.uploadFile(1, formData);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/files/upload",
        method: "post",
        headers: {
          "Content-Type": "multipart/form-data",
        },
        data: formData,
      });
      expect(result.fileName).toBe("plugin.jar");
    });
  });

  describe("deleteFile - 删除文件", () => {
    it("应该正确删除文件", async () => {
      request.mockResolvedValue(null);

      const result = await instanceApi.deleteFile(1, "/plugins/old.jar");

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/files",
        method: "delete",
        data: { path: "/plugins/old.jar" },
      });
      expect(result).toBeNull();
    });

    it("应该处理删除不存在的文件", async () => {
      request.mockRejectedValue(new Error("文件不存在"));

      await expect(
        instanceApi.deleteFile(1, "/nonexistent.txt"),
      ).rejects.toThrow("文件不存在");
    });
  });
});
