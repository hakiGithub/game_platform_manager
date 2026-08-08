/**
 * host.js Store 单元测试
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useHostStore } from "@/stores/host";

// Mock API
vi.mock("@/api/host", () => ({
  getHostList: vi.fn(),
  getHostDetail: vi.fn(),
  getHostStatus: vi.fn(),
  getHostResources: vi.fn(),
}));

describe("host store", () => {
  let hostStore;
  let hostApi;

  beforeEach(async () => {
    setActivePinia(createPinia());
    hostStore = useHostStore();
    hostApi = await import("@/api/host");
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("初始状态", () => {
    it("应该有正确的初始状态", () => {
      expect(hostStore.hostList).toEqual([]);
      expect(hostStore.currentHost).toBeNull();
      expect(hostStore.hostStatus).toEqual({});
      expect(hostStore.hostResources).toEqual({});
      expect(hostStore.loading).toBe(false);
      expect(hostStore.pagination).toEqual({
        current: 1,
        size: 10,
        total: 0,
        pages: 0,
      });
    });
  });

  describe("计算属性", () => {
    beforeEach(() => {
      hostStore.hostList = [
        { id: 1, name: "host1", status: 1 },
        { id: 2, name: "host2", status: 0 },
        { id: 3, name: "host3", status: 1 },
      ];
    });

    it("onlineHosts 应该返回在线主机列表", () => {
      expect(hostStore.onlineHosts).toHaveLength(2);
      expect(hostStore.onlineHosts.map((h) => h.id)).toEqual([1, 3]);
    });

    it("offlineHosts 应该返回离线主机列表", () => {
      expect(hostStore.offlineHosts).toHaveLength(1);
      expect(hostStore.offlineHosts[0].id).toBe(2);
    });

    it("onlineCount 应该返回在线主机数量", () => {
      expect(hostStore.onlineCount).toBe(2);
    });

    it("totalCount 应该返回总主机数量", () => {
      expect(hostStore.totalCount).toBe(3);
    });
  });

  describe("Actions", () => {
    describe("fetchHostList", () => {
      it("应该获取主机列表", async () => {
        hostApi.getHostList.mockResolvedValue({
          records: [
            { id: 1, name: "host1", status: 1 },
            { id: 2, name: "host2", status: 0 },
          ],
          current: 1,
          size: 10,
          total: 2,
          pages: 1,
        });

        const result = await hostStore.fetchHostList();

        expect(result.records).toHaveLength(2);
        expect(hostStore.hostList).toHaveLength(2);
        expect(hostStore.pagination.total).toBe(2);
        expect(hostStore.loading).toBe(false);
      });

      it("应该传递分页参数", async () => {
        hostApi.getHostList.mockResolvedValue({
          records: [],
          current: 2,
          size: 20,
          total: 0,
          pages: 0,
        });

        hostStore.updatePagination({ current: 2, size: 20 });

        await hostStore.fetchHostList();

        expect(hostApi.getHostList).toHaveBeenCalledWith(
          expect.objectContaining({
            current: 2,
            size: 20,
          }),
        );
      });

      it("应该处理空列表", async () => {
        hostApi.getHostList.mockResolvedValue({
          records: null,
        });

        await hostStore.fetchHostList();

        expect(hostStore.hostList).toEqual([]);
      });

      it("应该处理错误", async () => {
        hostApi.getHostList.mockRejectedValue(new Error("Network error"));

        await expect(hostStore.fetchHostList()).rejects.toThrow(
          "Network error",
        );
        expect(hostStore.loading).toBe(false);
      });
    });

    describe("fetchHostDetail", () => {
      it("应该获取主机详情", async () => {
        hostApi.getHostDetail.mockResolvedValue({
          id: 1,
          name: "host1",
          ip: "192.168.1.1",
          status: 1,
        });

        const result = await hostStore.fetchHostDetail(1);

        expect(result.id).toBe(1);
        expect(hostStore.currentHost.id).toBe(1);
      });

      it("应该处理错误", async () => {
        hostApi.getHostDetail.mockRejectedValue(new Error("Host not found"));

        await expect(hostStore.fetchHostDetail(999)).rejects.toThrow(
          "Host not found",
        );
      });
    });

    describe("fetchHostStatus", () => {
      it("应该获取主机状态", async () => {
        hostApi.getHostStatus.mockResolvedValue({
          status: 1,
          cpuUsage: 50,
          memoryUsage: 60,
          diskUsage: 40,
        });

        const result = await hostStore.fetchHostStatus(1);

        expect(result.cpuUsage).toBe(50);
        expect(hostStore.hostStatus[1].cpuUsage).toBe(50);
      });
    });

    describe("fetchHostResources", () => {
      it("应该获取主机资源使用情况", async () => {
        hostApi.getHostResources.mockResolvedValue({
          cpu: { usage: 50 },
          memory: { usage: 60 },
          disk: { usage: 40 },
        });

        const result = await hostStore.fetchHostResources(1);

        expect(result.cpu.usage).toBe(50);
        expect(hostStore.hostResources[1].cpu.usage).toBe(50);
      });
    });

    describe("fetchOnlineHostsResources", () => {
      it("应该批量获取在线主机资源", async () => {
        hostStore.hostList = [
          { id: 1, name: "host1", status: 1 },
          { id: 2, name: "host2", status: 0 },
          { id: 3, name: "host3", status: 1 },
        ];

        hostApi.getHostResources.mockResolvedValue({
          cpu: { usage: 50 },
        });

        await hostStore.fetchOnlineHostsResources();

        expect(hostApi.getHostResources).toHaveBeenCalledTimes(2);
        expect(hostApi.getHostResources).toHaveBeenCalledWith(1);
        expect(hostApi.getHostResources).toHaveBeenCalledWith(3);
      });

      it("应该处理单个主机获取失败", async () => {
        const consoleSpy = vi
          .spyOn(console, "error")
          .mockImplementation(() => {});

        hostStore.hostList = [
          { id: 1, name: "host1", status: 1 },
          { id: 2, name: "host2", status: 1 },
        ];

        hostApi.getHostResources
          .mockResolvedValueOnce({ cpu: { usage: 50 } })
          .mockRejectedValueOnce(new Error("Connection failed"));

        // 不应抛出错误
        await hostStore.fetchOnlineHostsResources();

        expect(consoleSpy).toHaveBeenCalled();

        consoleSpy.mockRestore();
      });
    });

    describe("updatePagination", () => {
      it("应该更新分页参数", () => {
        hostStore.updatePagination({ current: 2, size: 20 });

        expect(hostStore.pagination.current).toBe(2);
        expect(hostStore.pagination.size).toBe(20);
        expect(hostStore.pagination.total).toBe(0); // 保持原值
      });
    });

    describe("clearCurrentHost", () => {
      it("应该清除当前主机", () => {
        hostStore.currentHost = { id: 1, name: "host1" };

        hostStore.clearCurrentHost();

        expect(hostStore.currentHost).toBeNull();
      });
    });

    describe("clearAll", () => {
      it("应该清除所有状态", () => {
        hostStore.hostList = [{ id: 1 }];
        hostStore.currentHost = { id: 1 };
        hostStore.hostStatus = { 1: { cpuUsage: 50 } };
        hostStore.hostResources = { 1: { cpu: {} } };
        hostStore.pagination = { current: 2, size: 20, total: 100, pages: 5 };

        hostStore.clearAll();

        expect(hostStore.hostList).toEqual([]);
        expect(hostStore.currentHost).toBeNull();
        expect(hostStore.hostStatus).toEqual({});
        expect(hostStore.hostResources).toEqual({});
        expect(hostStore.pagination).toEqual({
          current: 1,
          size: 10,
          total: 0,
          pages: 0,
        });
      });
    });

    describe("updateHostStatusInList", () => {
      it("应该更新主机列表中的主机状态", () => {
        hostStore.hostList = [
          { id: 1, name: "host1", status: 0 },
          { id: 2, name: "host2", status: 1 },
        ];

        hostStore.updateHostStatusInList(1, { status: 1, cpuUsage: 50 });

        expect(hostStore.hostList[0].status).toBe(1);
        expect(hostStore.hostList[0].cpuUsage).toBe(50);
        expect(hostStore.hostList[0].name).toBe("host1"); // 保持原有属性
      });

      it("应该处理不存在的主机", () => {
        hostStore.hostList = [{ id: 1, name: "host1" }];

        // 不应抛出错误
        hostStore.updateHostStatusInList(999, { status: 1 });

        expect(hostStore.hostList).toHaveLength(1);
      });
    });
  });
});
