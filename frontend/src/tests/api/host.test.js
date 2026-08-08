/**
 * host.js API 单元测试
 * 测试主机列表、新增、更新、删除、连接测试等接口
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// Mock request 模块
vi.mock("@/utils/request", () => ({
  default: vi.fn(),
}));

describe("host API", () => {
  let request;
  let hostApi;

  beforeEach(async () => {
    vi.clearAllMocks();
    request = (await import("@/utils/request")).default;
    hostApi = await import("@/api/host");
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("getHostList - 获取主机列表", () => {
    it("应该使用默认参数获取主机列表", async () => {
      const mockResponse = {
        records: [
          { id: 1, name: "主机1", ip: "192.168.1.1", status: 1 },
          { id: 2, name: "主机2", ip: "192.168.1.2", status: 0 },
        ],
        current: 1,
        size: 10,
        total: 2,
        pages: 1,
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.getHostList();

      expect(request).toHaveBeenCalledWith({
        url: "/hosts",
        method: "get",
        params: undefined,
      });
      expect(result).toEqual(mockResponse);
    });

    it("应该使用分页参数获取主机列表", async () => {
      const params = {
        current: 2,
        size: 20,
        name: "测试主机",
        ip: "192.168",
        status: 1,
      };
      const mockResponse = {
        records: [{ id: 3, name: "测试主机", ip: "192.168.1.3", status: 1 }],
        current: 2,
        size: 20,
        total: 21,
        pages: 2,
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.getHostList(params);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts",
        method: "get",
        params,
      });
      expect(result.total).toBe(21);
    });

    it("应该处理空列表", async () => {
      const mockResponse = {
        records: [],
        current: 1,
        size: 10,
        total: 0,
        pages: 0,
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.getHostList();

      expect(result.records).toEqual([]);
      expect(result.total).toBe(0);
    });

    it("应该处理获取列表失败", async () => {
      request.mockRejectedValue(new Error("网络错误"));

      await expect(hostApi.getHostList()).rejects.toThrow("网络错误");
    });
  });

  describe("getHostDetail - 获取主机详情", () => {
    it("应该正确获取主机详情", async () => {
      const mockResponse = {
        id: 1,
        name: "主机1",
        ip: "192.168.1.1",
        sshPort: 22,
        sshUsername: "root",
        tags: '["生产环境", "游戏服务器"]',
        remark: "主要游戏服务器",
        status: 1,
        createdAt: "2024-01-01T00:00:00Z",
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.getHostDetail(1);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1",
        method: "get",
      });
      expect(result.id).toBe(1);
      expect(result.name).toBe("主机1");
    });

    it("应该处理主机不存在", async () => {
      request.mockRejectedValue(new Error("主机不存在"));

      await expect(hostApi.getHostDetail(999)).rejects.toThrow("主机不存在");
    });
  });

  describe("createHost - 新增主机", () => {
    it("应该使用密码认证方式创建主机", async () => {
      const hostData = {
        name: "新主机",
        ip: "192.168.1.100",
        sshPort: 22,
        sshUsername: "root",
        sshPassword: "password123",
        tags: '["测试环境"]',
        remark: "测试用主机",
      };
      const mockResponse = { id: 3 };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.createHost(hostData);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts",
        method: "post",
        data: hostData,
      });
      expect(result.id).toBe(3);
    });

    it("应该使用私钥认证方式创建主机", async () => {
      const hostData = {
        name: "新主机",
        ip: "192.168.1.101",
        sshPort: 22,
        sshUsername: "root",
        sshPrivateKey: "-----BEGIN RSA PRIVATE KEY-----\n...",
        tags: '["生产环境"]',
      };
      request.mockResolvedValue({ id: 4 });

      await hostApi.createHost(hostData);

      expect(request).toHaveBeenCalledWith(
        expect.objectContaining({
          url: "/hosts",
          method: "post",
          data: expect.objectContaining({
            sshPrivateKey: hostData.sshPrivateKey,
          }),
        }),
      );
    });

    it("应该处理创建失败 - IP已存在", async () => {
      const hostData = {
        name: "重复主机",
        ip: "192.168.1.1",
        sshUsername: "root",
      };
      request.mockRejectedValue(new Error("该IP地址的主机已存在"));

      await expect(hostApi.createHost(hostData)).rejects.toThrow(
        "该IP地址的主机已存在",
      );
    });

    it("应该处理创建失败 - 参数验证失败", async () => {
      const hostData = {
        name: "",
        ip: "invalid-ip",
      };
      request.mockRejectedValue(new Error("参数验证失败：IP地址格式不正确"));

      await expect(hostApi.createHost(hostData)).rejects.toThrow(
        "参数验证失败",
      );
    });
  });

  describe("updateHost - 更新主机", () => {
    it("应该正确更新主机信息", async () => {
      const updateData = {
        name: "更新后的主机名",
        sshPort: 2222,
        remark: "更新后的备注",
      };
      request.mockResolvedValue(null);

      const result = await hostApi.updateHost(1, updateData);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1",
        method: "put",
        data: updateData,
      });
      expect(result).toBeNull();
    });

    it("应该处理更新不存在的", async () => {
      request.mockRejectedValue(new Error("主机不存在"));

      await expect(hostApi.updateHost(999, { name: "test" })).rejects.toThrow(
        "主机不存在",
      );
    });
  });

  describe("deleteHost - 删除主机", () => {
    it("应该正确删除主机", async () => {
      request.mockResolvedValue(null);

      const result = await hostApi.deleteHost(1);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1",
        method: "delete",
      });
      expect(result).toBeNull();
    });

    it("应该处理删除不存在的主机", async () => {
      request.mockRejectedValue(new Error("主机不存在"));

      await expect(hostApi.deleteHost(999)).rejects.toThrow("主机不存在");
    });

    it("应该处理删除有关联实例的主机", async () => {
      request.mockRejectedValue(new Error("该主机下存在游戏实例，无法删除"));

      await expect(hostApi.deleteHost(1)).rejects.toThrow(
        "该主机下存在游戏实例，无法删除",
      );
    });
  });

  describe("testHostConnection - 测试主机连接", () => {
    it("应该成功测试主机连接", async () => {
      const mockResponse = {
        success: true,
        message: "连接成功",
        latency: 15,
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.testHostConnection(1);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/test",
        method: "post",
      });
      expect(result.success).toBe(true);
      expect(result.latency).toBe(15);
    });

    it("应该处理连接失败", async () => {
      const mockResponse = {
        success: false,
        message: "连接失败：认证失败",
        latency: -1,
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.testHostConnection(1);

      expect(result.success).toBe(false);
      expect(result.message).toContain("认证失败");
    });

    it("应该处理主机不存在", async () => {
      request.mockRejectedValue(new Error("主机不存在"));

      await expect(hostApi.testHostConnection(999)).rejects.toThrow(
        "主机不存在",
      );
    });
  });

  describe("getHostStatus - 获取主机状态", () => {
    it("应该正确获取主机状态", async () => {
      const mockResponse = {
        status: 1,
        cpuUsage: 45.5,
        memoryUsage: 60.2,
        diskUsage: 75.0,
        uptime: 86400,
        loadAverage: "0.5 0.3 0.2",
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.getHostStatus(1);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/status",
        method: "get",
      });
      expect(result.status).toBe(1);
      expect(result.cpuUsage).toBe(45.5);
    });
  });

  describe("scanPorts - 扫描端口", () => {
    it("应该扫描指定范围的端口", async () => {
      const mockResponse = {
        ports: [
          { port: 22, protocol: "tcp", service: "ssh", pid: 1234 },
          { port: 80, protocol: "tcp", service: "http", pid: 5678 },
        ],
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.scanPorts(1, {
        startPort: 1,
        endPort: 1000,
      });

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/ports",
        method: "get",
        params: { startPort: 1, endPort: 1000 },
      });
      expect(result.ports).toHaveLength(2);
    });

    it("应该使用默认端口范围", async () => {
      request.mockResolvedValue({ ports: [] });

      await hostApi.scanPorts(1);

      expect(request).toHaveBeenCalledWith(
        expect.objectContaining({
          url: "/hosts/1/ports",
          method: "get",
        }),
      );
    });
  });

  describe("getHostResources - 获取主机资源使用情况", () => {
    it("应该正确获取资源使用情况", async () => {
      const mockResponse = {
        cpu: { usage: 30.5, cores: 8 },
        memory: { usage: 65.2, total: 16384, used: 10678 },
        disk: { usage: 45.0, total: 512000, used: 230400 },
        network: { rx: 1024000, tx: 512000 },
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.getHostResources(1);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/resources",
        method: "get",
      });
      expect(result.cpu.cores).toBe(8);
      expect(result.memory.total).toBe(16384);
    });
  });

  describe("previewHostsRefresh - 预检 hosts 刷新", () => {
    it("应该正确调用预检接口", async () => {
      const mockResponse = {
        hostLanIp: "192.168.111.253",
        hostname: "haki-pc",
        domainsToRefresh: ["raw.githubusercontent.com", "github.com"],
        sudoAvailable: true,
        needsSudoPassword: false,
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.previewHostsRefresh(1);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/hosts-preview",
        method: "get",
      });
      expect(result.domainsToRefresh).toHaveLength(2);
      expect(result.sudoAvailable).toBe(true);
    });

    it("应该处理预检失败", async () => {
      request.mockRejectedValue(new Error("SSH 连接失败"));

      await expect(hostApi.previewHostsRefresh(999)).rejects.toThrow(
        "SSH 连接失败",
      );
    });
  });

  describe("refreshHosts - 执行 hosts 刷新", () => {
    it("应该使用 POST 方法调用刷新接口，带 sudo 密码", async () => {
      const mockResponse = {
        success: true,
        backupPath: "/etc/hosts.bak.20260718120000",
        refreshedDomains: ["github.com", "raw.githubusercontent.com"],
        hostLanIp: "192.168.111.253",
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.refreshHosts(1, "mypassword");

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/hosts-refresh",
        method: "post",
        data: { sudoPassword: "mypassword", selectedDomains: null },
      });
      expect(result.success).toBe(true);
      expect(result.refreshedDomains).toHaveLength(2);
    });

    it("sudoPassword 为 null 时应正确传递", async () => {
      const mockResponse = {
        success: true,
        backupPath: "/etc/hosts.bak.20260718120000",
        refreshedDomains: ["github.com"],
        hostLanIp: "192.168.111.253",
      };
      request.mockResolvedValue(mockResponse);

      await hostApi.refreshHosts(1, null);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/hosts-refresh",
        method: "post",
        data: { sudoPassword: null, selectedDomains: null },
      });
    });

    it("selectedDomains 非空时应正确传递选中的域名清单", async () => {
      const mockResponse = {
        success: true,
        backupPath: "/etc/hosts.bak.20260718120000",
        refreshedDomains: ["github.com", "raw.githubusercontent.com"],
        hostLanIp: "192.168.111.253",
      };
      request.mockResolvedValue(mockResponse);

      const selected = ["github.com", "raw.githubusercontent.com"];
      await hostApi.refreshHosts(1, "mypassword", selected);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/hosts-refresh",
        method: "post",
        data: {
          sudoPassword: "mypassword",
          selectedDomains: selected,
        },
      });
    });

    it("应该处理刷新失败 - sudo 密码错误", async () => {
      request.mockRejectedValue(new Error("sudo 密码错误，请重试"));

      await expect(hostApi.refreshHosts(1, "wrongpwd")).rejects.toThrow(
        "sudo 密码错误",
      );
    });
  });
});
