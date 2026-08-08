import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useBackupStore } from "@/stores/backup";
import * as backupApi from "@/api/backup";

// Mock backup API
vi.mock("@/api/backup", () => ({
  getBackupList: vi.fn(),
  getBackupDetail: vi.fn(),
  getBackupStats: vi.fn(),
  createDatabaseBackup: vi.fn(),
  createFilesBackup: vi.fn(),
  getBackupProgress: vi.fn(),
  cancelBackup: vi.fn(),
  restoreBackup: vi.fn(),
  getRestoreProgress: vi.fn(),
  deleteBackup: vi.fn(),
  batchDeleteBackups: vi.fn(),
  downloadBackup: vi.fn(),
  verifyBackup: vi.fn(),
}));

describe("Backup Store", () => {
  let store;

  beforeEach(() => {
    setActivePinia(createPinia());
    store = useBackupStore();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  describe("State", () => {
    it("should have correct initial state", () => {
      expect(store.backupList).toEqual([]);
      expect(store.currentBackup).toBeNull();
      expect(store.loading).toBe(false);
      expect(store.pagination).toEqual({
        current: 1,
        size: 10,
        total: 0,
        pages: 0,
      });
      expect(store.activeBackupProgress.polling).toBe(false);
      expect(store.activeRestoreProgress.polling).toBe(false);
    });
  });

  describe("Getters", () => {
    it("should compute hasActiveBackup correctly", () => {
      expect(store.hasActiveBackup).toBe(false);
      store.activeBackupProgress.polling = true;
      expect(store.hasActiveBackup).toBe(true);
    });

    it("should compute hasActiveRestore correctly", () => {
      expect(store.hasActiveRestore).toBe(false);
      store.activeRestoreProgress.polling = true;
      expect(store.hasActiveRestore).toBe(true);
    });

    it("should group backups by status", () => {
      store.backupList = [
        { id: 1, status: "completed" },
        { id: 2, status: "running" },
        { id: 3, status: "failed" },
        { id: 4, status: "completed" },
      ];

      expect(store.backupsByStatus.completed).toHaveLength(2);
      expect(store.backupsByStatus.running).toHaveLength(1);
      expect(store.backupsByStatus.failed).toHaveLength(1);
    });

    it("should filter database backups", () => {
      store.backupList = [
        { id: 1, type: "database" },
        { id: 2, type: "files" },
        { id: 3, type: "database" },
      ];

      expect(store.databaseBackups).toHaveLength(2);
      expect(store.fileBackups).toHaveLength(1);
    });
  });

  describe("Actions - fetchBackupList", () => {
    it("should fetch and store backup list", async () => {
      const mockData = {
        current: 1,
        size: 10,
        total: 2,
        pages: 1,
        records: [
          { id: 1, name: "backup1" },
          { id: 2, name: "backup2" },
        ],
      };
      backupApi.getBackupList.mockResolvedValue(mockData);

      const result = await store.fetchBackupList(1);

      expect(backupApi.getBackupList).toHaveBeenCalledWith(1, {
        current: 1,
        size: 10,
      });
      expect(store.backupList).toEqual(mockData.records);
      expect(store.pagination.total).toBe(2);
      expect(result).toEqual(mockData);
    });

    it("should handle fetch error", async () => {
      backupApi.getBackupList.mockRejectedValue(new Error("Network error"));

      await expect(store.fetchBackupList(1)).rejects.toThrow("Network error");
      expect(store.loading).toBe(false);
    });
  });

  describe("Actions - createDatabase", () => {
    it("should create database backup and start polling", async () => {
      const mockData = { id: 1, name: "test_backup", status: "running" };
      backupApi.createDatabaseBackup.mockResolvedValue(mockData);
      backupApi.getBackupProgress.mockResolvedValue({
        progress: 50,
        status: "running",
        completed: false,
      });

      const result = await store.createDatabase(1, { name: "test_backup" });

      expect(backupApi.createDatabaseBackup).toHaveBeenCalledWith(1, {
        name: "test_backup",
      });
      expect(result).toEqual(mockData);
      expect(store.activeBackupProgress.polling).toBe(true);

      // Cleanup polling
      store.stopBackupProgressPolling();
    });
  });

  describe("Actions - createFiles", () => {
    it("should create files backup with paths", async () => {
      const mockData = { id: 2, name: "files_backup", status: "running" };
      backupApi.createFilesBackup.mockResolvedValue(mockData);
      backupApi.getBackupProgress.mockResolvedValue({
        progress: 30,
        status: "running",
        completed: false,
      });

      const backupData = {
        name: "files_backup",
        includePaths: ["config/"],
        excludePaths: ["logs/"],
      };
      const result = await store.createFiles(1, backupData);

      expect(backupApi.createFilesBackup).toHaveBeenCalledWith(1, backupData);
      expect(result).toEqual(mockData);

      // Cleanup polling
      store.stopBackupProgressPolling();
    });
  });

  describe("Actions - Backup Progress Polling", () => {
    it("should poll backup progress until completed", async () => {
      backupApi.getBackupProgress
        .mockResolvedValueOnce({
          progress: 25,
          status: "running",
          message: "Step 1",
          completed: false,
        })
        .mockResolvedValueOnce({
          progress: 50,
          status: "running",
          message: "Step 2",
          completed: false,
        })
        .mockResolvedValueOnce({
          progress: 100,
          status: "completed",
          message: "Done",
          completed: true,
        });

      const onProgress = vi.fn();
      const onComplete = vi.fn();

      store.startBackupProgressPolling(1, 1, onProgress, onComplete);

      // Wait for initial call
      await vi.advanceTimersByTimeAsync(0);
      expect(store.activeBackupProgress.progress).toBe(25);

      // Advance and check second poll
      await vi.advanceTimersByTimeAsync(2000);
      expect(store.activeBackupProgress.progress).toBe(50);

      // Advance and check completion
      await vi.advanceTimersByTimeAsync(2000);
      expect(store.activeBackupProgress.progress).toBe(100);
      expect(store.activeBackupProgress.completed).toBe(true);
      expect(onComplete).toHaveBeenCalled();
    });

    it("should stop polling when cancel is called", async () => {
      backupApi.cancelBackup.mockResolvedValue({ success: true });

      store.startBackupProgressPolling(1, 1);
      expect(store.activeBackupProgress.polling).toBe(true);

      await store.cancel(1, 1);

      expect(backupApi.cancelBackup).toHaveBeenCalledWith(1, 1);
      expect(store.activeBackupProgress.polling).toBe(false);
    });
  });

  describe("Actions - restore", () => {
    it("should start restore and poll progress", async () => {
      const mockData = { restoreId: "restore-123", status: "running" };
      backupApi.restoreBackup.mockResolvedValue(mockData);
      backupApi.getRestoreProgress.mockResolvedValue({
        progress: 50,
        status: "running",
        completed: false,
      });

      const result = await store.restore(1, 1);

      expect(backupApi.restoreBackup).toHaveBeenCalledWith(1, 1, {});
      expect(result).toEqual(mockData);
      expect(store.activeRestoreProgress.polling).toBe(true);

      // Cleanup
      store.stopRestoreProgressPolling();
    });

    it("should pass restore options to API", async () => {
      const mockData = { restoreId: "restore-123" };
      backupApi.restoreBackup.mockResolvedValue(mockData);
      backupApi.getRestoreProgress.mockResolvedValue({
        progress: 0,
        status: "running",
        completed: false,
      });

      const options = {
        restoreDatabase: true,
        restoreFiles: false,
      };
      await store.restore(1, 1, options);

      expect(backupApi.restoreBackup).toHaveBeenCalledWith(1, 1, options);

      // Cleanup
      store.stopRestoreProgressPolling();
    });
  });

  describe("Actions - remove", () => {
    it("should delete backup and update list", async () => {
      store.backupList = [
        { id: 1, name: "backup1" },
        { id: 2, name: "backup2" },
      ];
      store.pagination.total = 2;
      backupApi.deleteBackup.mockResolvedValue({ success: true });

      await store.remove(1, 1);

      expect(backupApi.deleteBackup).toHaveBeenCalledWith(1, 1);
      expect(store.backupList).toHaveLength(1);
      expect(store.backupList[0].id).toBe(2);
      expect(store.pagination.total).toBe(1);
    });
  });

  describe("Actions - batchRemove", () => {
    it("should batch delete backups", async () => {
      store.backupList = [
        { id: 1, name: "backup1" },
        { id: 2, name: "backup2" },
        { id: 3, name: "backup3" },
      ];
      store.pagination.total = 3;
      backupApi.batchDeleteBackups.mockResolvedValue({
        success: true,
        deletedCount: 2,
      });

      await store.batchRemove(1, [1, 2]);

      expect(backupApi.batchDeleteBackups).toHaveBeenCalledWith(1, [1, 2]);
      expect(store.backupList).toHaveLength(1);
      expect(store.backupList[0].id).toBe(3);
      expect(store.pagination.total).toBe(1);
    });
  });

  describe("Actions - download", () => {
    it("should download backup file", async () => {
      const mockBlob = new Blob(["test"], { type: "application/zip" });
      backupApi.downloadBackup.mockResolvedValue(mockBlob);

      // Mock URL methods
      const mockUrl = "blob:test-url";
      global.URL.createObjectURL = vi.fn(() => mockUrl);
      global.URL.revokeObjectURL = vi.fn();

      // Mock document methods
      const mockLink = {
        href: "",
        download: "",
        click: vi.fn(),
      };
      document.createElement = vi.fn(() => mockLink);
      document.body.appendChild = vi.fn();
      document.body.removeChild = vi.fn();

      const result = await store.download(1, 1, "test-backup.zip");

      expect(backupApi.downloadBackup).toHaveBeenCalledWith(1, 1);
      expect(mockLink.download).toBe("test-backup.zip");
      expect(mockLink.click).toHaveBeenCalled();
      expect(result).toBe(true);
    });
  });

  describe("Actions - verify", () => {
    it("should verify backup and update current backup", async () => {
      const mockData = { valid: true, message: "Backup is valid" };
      backupApi.verifyBackup.mockResolvedValue(mockData);

      store.currentBackup = { id: 1, name: "backup1" };

      const result = await store.verify(1, 1);

      expect(backupApi.verifyBackup).toHaveBeenCalledWith(1, 1);
      expect(result).toEqual(mockData);
      expect(store.currentBackup.verified).toBe(true);
      expect(store.currentBackup.verifyMessage).toBe("Backup is valid");
    });
  });

  describe("Helper Functions", () => {
    it("should return correct status text", () => {
      expect(store.getBackupStatusText("running")).toBe("备份中");
      expect(store.getBackupStatusText("completed")).toBe("成功");
      expect(store.getBackupStatusText("failed")).toBe("失败");
      expect(store.getBackupStatusText("cancelled")).toBe("已取消");
      expect(store.getBackupStatusText("unknown")).toBe("unknown");
    });

    it("should return correct status type", () => {
      expect(store.getBackupStatusType("running")).toBe("warning");
      expect(store.getBackupStatusType("completed")).toBe("success");
      expect(store.getBackupStatusType("failed")).toBe("danger");
      expect(store.getBackupStatusType("cancelled")).toBe("info");
    });

    it("should return correct type text", () => {
      expect(store.getBackupTypeText("database")).toBe("数据库");
      expect(store.getBackupTypeText("files")).toBe("文件");
      expect(store.getBackupTypeText("full")).toBe("完整备份");
    });
  });

  describe("Actions - clearAll", () => {
    it("should reset all state", () => {
      store.backupList = [{ id: 1 }];
      store.currentBackup = { id: 1 };
      store.pagination.total = 10;
      store.activeBackupProgress.polling = true;

      store.clearAll();

      expect(store.backupList).toEqual([]);
      expect(store.currentBackup).toBeNull();
      expect(store.pagination.total).toBe(0);
      expect(store.activeBackupProgress.polling).toBe(false);
    });
  });
});
