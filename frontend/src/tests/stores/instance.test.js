/**
 * instance.js Store 单元测试
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useInstanceStore } from "@/stores/instance";

// Mock API
vi.mock("@/api/instance", () => ({
  getInstanceList: vi.fn(),
  getInstanceDetail: vi.fn(),
  getInstanceStatus: vi.fn(),
  getInstanceLogs: vi.fn(),
  startInstance: vi.fn(),
  stopInstance: vi.fn(),
  restartInstance: vi.fn(),
}));

describe("instance store", () => {
  let instanceStore;
  let instanceApi;

  beforeEach(async () => {
    setActivePinia(createPinia());
    instanceStore = useInstanceStore();
    instanceApi = await import("@/api/instance");
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("初始状态", () => {
    it("应该有正确的初始状态", () => {
      expect(instanceStore.instanceList).toEqual([]);
      expect(instanceStore.currentInstance).toBeNull();
      expect(instanceStore.instanceStatus).toEqual({});
      expect(instanceStore.instanceLogs).toEqual({});
      expect(instanceStore.loading).toBe(false);
      expect(instanceStore.pagination).toEqual({
        current: 1,
        size: 10,
        total: 0,
        pages: 0,
      });
    });
  });

  describe("计算属性", () => {
    beforeEach(() => {
      // status 为 InstanceStatus wireKey 字符串（ADR-0005）
      instanceStore.instanceList = [
        { id: 1, name: "instance1", status: "running" },
        { id: 2, name: "instance2", status: "stopped" },
        { id: 3, name: "instance3", status: "error" },
        { id: 4, name: "instance4", status: "running" },
      ];
    });

    it("runningInstances 应该返回运行中的实例", () => {
      expect(instanceStore.runningInstances).toHaveLength(2);
      expect(instanceStore.runningInstances.map((i) => i.id)).toEqual([1, 4]);
    });

    it("stoppedInstances 应该返回已停止的实例", () => {
      expect(instanceStore.stoppedInstances).toHaveLength(1);
      expect(instanceStore.stoppedInstances[0].id).toBe(2);
    });

    it("errorInstances 应该返回异常的实例", () => {
      expect(instanceStore.errorInstances).toHaveLength(1);
      expect(instanceStore.errorInstances[0].id).toBe(3);
    });

    it("runningCount 应该返回运行中实例数量", () => {
      expect(instanceStore.runningCount).toBe(2);
    });

    it("stoppedCount 应该返回已停止实例数量", () => {
      expect(instanceStore.stoppedCount).toBe(1);
    });

    it("errorCount 应该返回异常实例数量", () => {
      expect(instanceStore.errorCount).toBe(1);
    });

    it("totalCount 应该返回总实例数量", () => {
      expect(instanceStore.totalCount).toBe(4);
    });
  });

  describe("Actions", () => {
    describe("fetchInstanceList", () => {
      it("应该获取实例列表", async () => {
        instanceApi.getInstanceList.mockResolvedValue({
          records: [
            { id: 1, name: "instance1", status: 1 },
            { id: 2, name: "instance2", status: 0 },
          ],
          current: 1,
          size: 10,
          total: 2,
          pages: 1,
        });

        const result = await instanceStore.fetchInstanceList();

        expect(result.records).toHaveLength(2);
        expect(instanceStore.instanceList).toHaveLength(2);
        expect(instanceStore.pagination.total).toBe(2);
        expect(instanceStore.loading).toBe(false);
      });

      it("应该传递分页参数", async () => {
        instanceApi.getInstanceList.mockResolvedValue({
          records: [],
          current: 2,
          size: 20,
          total: 0,
          pages: 0,
        });

        instanceStore.updatePagination({ current: 2, size: 20 });

        await instanceStore.fetchInstanceList();

        expect(instanceApi.getInstanceList).toHaveBeenCalledWith(
          expect.objectContaining({
            current: 2,
            size: 20,
          }),
        );
      });

      it("应该处理空列表", async () => {
        instanceApi.getInstanceList.mockResolvedValue({
          records: null,
        });

        await instanceStore.fetchInstanceList();

        expect(instanceStore.instanceList).toEqual([]);
      });

      it("应该处理错误", async () => {
        instanceApi.getInstanceList.mockRejectedValue(
          new Error("Network error"),
        );

        await expect(instanceStore.fetchInstanceList()).rejects.toThrow(
          "Network error",
        );
        expect(instanceStore.loading).toBe(false);
      });
    });

    describe("fetchInstanceDetail", () => {
      it("应该获取实例详情", async () => {
        instanceApi.getInstanceDetail.mockResolvedValue({
          id: 1,
          name: "instance1",
          status: 1,
          port: 25565,
        });

        const result = await instanceStore.fetchInstanceDetail(1);

        expect(result.id).toBe(1);
        expect(instanceStore.currentInstance.id).toBe(1);
      });

      it("应该处理错误", async () => {
        instanceApi.getInstanceDetail.mockRejectedValue(
          new Error("Instance not found"),
        );

        await expect(instanceStore.fetchInstanceDetail(999)).rejects.toThrow(
          "Instance not found",
        );
      });
    });

    describe("fetchInstanceStatus", () => {
      it("应该获取实例状态", async () => {
        instanceApi.getInstanceStatus.mockResolvedValue({
          status: 1,
          processId: 12345,
          uptime: 3600,
        });

        const result = await instanceStore.fetchInstanceStatus(1);

        expect(result.processId).toBe(12345);
        expect(instanceStore.instanceStatus[1].processId).toBe(12345);
      });
    });

    describe("fetchInstanceLogs", () => {
      it("应该获取实例日志", async () => {
        instanceApi.getInstanceLogs.mockResolvedValue({
          logs: ["log1", "log2", "log3"],
          total: 3,
        });

        const result = await instanceStore.fetchInstanceLogs(1);

        expect(result.logs).toHaveLength(3);
        expect(instanceStore.instanceLogs[1]).toEqual(["log1", "log2", "log3"]);
      });

      it("应该传递查询参数", async () => {
        instanceApi.getInstanceLogs.mockResolvedValue({
          logs: [],
        });

        await instanceStore.fetchInstanceLogs(1, {
          lines: 50,
          keyword: "error",
        });

        expect(instanceApi.getInstanceLogs).toHaveBeenCalledWith(1, {
          lines: 50,
          keyword: "error",
        });
      });
    });

    describe("start", () => {
      it("应该启动实例", async () => {
        instanceStore.instanceList = [{ id: 1, name: "instance1", status: 0 }];

        instanceApi.startInstance.mockResolvedValue({
          success: true,
          processId: 12345,
        });

        const result = await instanceStore.start(1);

        expect(result.processId).toBe(12345);
        expect(instanceStore.instanceList[0].status).toBe(1);
        expect(instanceStore.instanceList[0].processId).toBe(12345);
      });
    });

    describe("stop", () => {
      it("应该停止实例", async () => {
        instanceStore.instanceList = [
          { id: 1, name: "instance1", status: 1, processId: 12345 },
        ];

        instanceApi.stopInstance.mockResolvedValue({
          success: true,
        });

        const result = await instanceStore.stop(1);

        expect(result.success).toBe(true);
        expect(instanceStore.instanceList[0].status).toBe(0);
        expect(instanceStore.instanceList[0].processId).toBeNull();
      });

      it("应该支持强制停止", async () => {
        instanceStore.instanceList = [{ id: 1, name: "instance1", status: 1 }];

        instanceApi.stopInstance.mockResolvedValue({ success: true });

        await instanceStore.stop(1, { force: true });

        expect(instanceApi.stopInstance).toHaveBeenCalledWith(1, {
          force: true,
        });
      });
    });

    describe("restart", () => {
      it("应该重启实例", async () => {
        instanceStore.instanceList = [{ id: 1, name: "instance1", status: 1 }];

        instanceApi.restartInstance.mockResolvedValue({
          success: true,
          processId: 54321,
        });

        const result = await instanceStore.restart(1);

        expect(result.processId).toBe(54321);
        expect(instanceStore.instanceList[0].status).toBe(1);
        expect(instanceStore.instanceList[0].processId).toBe(54321);
      });
    });

    describe("updatePagination", () => {
      it("应该更新分页参数", () => {
        instanceStore.updatePagination({ current: 2, size: 20 });

        expect(instanceStore.pagination.current).toBe(2);
        expect(instanceStore.pagination.size).toBe(20);
      });
    });

    describe("clearCurrentInstance", () => {
      it("应该清除当前实例", () => {
        instanceStore.currentInstance = { id: 1, name: "instance1" };

        instanceStore.clearCurrentInstance();

        expect(instanceStore.currentInstance).toBeNull();
      });
    });

    describe("clearAll", () => {
      it("应该清除所有状态", () => {
        instanceStore.instanceList = [{ id: 1 }];
        instanceStore.currentInstance = { id: 1 };
        instanceStore.instanceStatus = { 1: { status: 1 } };
        instanceStore.instanceLogs = { 1: ["log"] };
        instanceStore.pagination = {
          current: 2,
          size: 20,
          total: 100,
          pages: 5,
        };

        instanceStore.clearAll();

        expect(instanceStore.instanceList).toEqual([]);
        expect(instanceStore.currentInstance).toBeNull();
        expect(instanceStore.instanceStatus).toEqual({});
        expect(instanceStore.instanceLogs).toEqual({});
        expect(instanceStore.pagination).toEqual({
          current: 1,
          size: 10,
          total: 0,
          pages: 0,
        });
      });
    });

    describe("updateInstanceStatusInList", () => {
      it("应该更新实例列表中的状态", () => {
        instanceStore.instanceList = [
          { id: 1, name: "instance1", status: 0 },
          { id: 2, name: "instance2", status: 1 },
        ];

        instanceStore.updateInstanceStatusInList(1, {
          status: 1,
          processId: 12345,
        });

        expect(instanceStore.instanceList[0].status).toBe(1);
        expect(instanceStore.instanceList[0].processId).toBe(12345);
      });

      it("应该同时更新当前实例", () => {
        instanceStore.instanceList = [{ id: 1, name: "instance1", status: 0 }];
        instanceStore.currentInstance = { id: 1, name: "instance1", status: 0 };

        instanceStore.updateInstanceStatusInList(1, { status: 1 });

        expect(instanceStore.currentInstance.status).toBe(1);
      });

      it("不应更新不匹配的当前实例", () => {
        instanceStore.instanceList = [{ id: 1, name: "instance1", status: 0 }];
        instanceStore.currentInstance = { id: 2, name: "instance2", status: 0 };

        instanceStore.updateInstanceStatusInList(1, { status: 1 });

        expect(instanceStore.currentInstance.status).toBe(0);
      });
    });
  });
});
