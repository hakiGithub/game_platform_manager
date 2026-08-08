import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  getBackupList,
  getBackupDetail,
  createDatabaseBackup,
  createFilesBackup,
  getBackupProgress,
  cancelBackup,
  restoreBackup,
  getRestoreProgress,
  deleteBackup,
  batchDeleteBackups,
  downloadBackup,
  verifyBackup,
  getBackupStats,
} from "@/api/backup";
import request from "@/utils/request";

// Mock request module
vi.mock("@/utils/request", () => ({
  default: vi.fn(),
}));

describe("Backup API", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("getBackupList", () => {
    it("should fetch backup list with correct URL and params", async () => {
      const mockData = {
        current: 1,
        size: 10,
        total: 2,
        pages: 1,
        records: [
          { id: 1, name: "backup1", type: "database", status: "completed" },
          { id: 2, name: "backup2", type: "files", status: "running" },
        ],
      };
      request.mockResolvedValue(mockData);

      const result = await getBackupList(1, { current: 1, size: 10 });

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups",
        method: "get",
        params: { current: 1, size: 10 },
      });
      expect(result).toEqual(mockData);
    });

    it("should support filter params", async () => {
      const mockData = { records: [], total: 0 };
      request.mockResolvedValue(mockData);

      await getBackupList(1, { type: "database", status: "completed" });

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups",
        method: "get",
        params: { type: "database", status: "completed" },
      });
    });
  });

  describe("getBackupDetail", () => {
    it("should fetch backup detail with correct URL", async () => {
      const mockData = { id: 1, name: "backup1", type: "database" };
      request.mockResolvedValue(mockData);

      const result = await getBackupDetail(1, 1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/1",
        method: "get",
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("createDatabaseBackup", () => {
    it("should create database backup with correct data", async () => {
      const mockData = { id: 1, name: "test_backup", status: "pending" };
      request.mockResolvedValue(mockData);

      const backupData = {
        name: "test_backup",
        description: "Test description",
      };
      const result = await createDatabaseBackup(1, backupData);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/database",
        method: "post",
        data: backupData,
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("createFilesBackup", () => {
    it("should create files backup with paths configuration", async () => {
      const mockData = { id: 2, name: "files_backup", status: "pending" };
      request.mockResolvedValue(mockData);

      const backupData = {
        name: "files_backup",
        description: "Files backup",
        includePaths: ["config/", "world/"],
        excludePaths: ["logs/"],
      };
      const result = await createFilesBackup(1, backupData);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/files",
        method: "post",
        data: backupData,
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("getBackupProgress", () => {
    it("should fetch backup progress", async () => {
      const mockData = {
        progress: 50,
        status: "running",
        message: "Backing up database...",
        currentStep: "Exporting tables",
        completed: false,
      };
      request.mockResolvedValue(mockData);

      const result = await getBackupProgress(1, 1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/1/progress",
        method: "get",
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("cancelBackup", () => {
    it("should cancel backup", async () => {
      const mockData = { success: true, message: "Backup cancelled" };
      request.mockResolvedValue(mockData);

      const result = await cancelBackup(1, 1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/1/cancel",
        method: "post",
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("restoreBackup", () => {
    it("should restore backup with default options", async () => {
      const mockData = { restoreId: "restore-123", status: "running" };
      request.mockResolvedValue(mockData);

      const result = await restoreBackup(1, 1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/1/restore",
        method: "post",
        data: {},
      });
      expect(result).toEqual(mockData);
    });

    it("should restore backup with custom options", async () => {
      const mockData = { restoreId: "restore-123", status: "running" };
      request.mockResolvedValue(mockData);

      const options = {
        restoreDatabase: true,
        restoreFiles: false,
        targetPath: "/custom/path",
      };
      const result = await restoreBackup(1, 1, options);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/1/restore",
        method: "post",
        data: options,
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("getRestoreProgress", () => {
    it("should fetch restore progress", async () => {
      const mockData = {
        progress: 75,
        status: "running",
        message: "Restoring files...",
        completed: false,
      };
      request.mockResolvedValue(mockData);

      const result = await getRestoreProgress(1, "restore-123");

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/restore-progress/restore-123",
        method: "get",
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("deleteBackup", () => {
    it("should delete backup", async () => {
      const mockData = { success: true, message: "Backup deleted" };
      request.mockResolvedValue(mockData);

      const result = await deleteBackup(1, 1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/1",
        method: "delete",
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("batchDeleteBackups", () => {
    it("should batch delete backups", async () => {
      const mockData = { success: true, deletedCount: 2 };
      request.mockResolvedValue(mockData);

      const backupIds = [1, 2, 3];
      const result = await batchDeleteBackups(1, backupIds);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/batch",
        method: "delete",
        data: { backupIds },
      });
      expect(result).toEqual(mockData);
    });
  });

  describe("downloadBackup", () => {
    it("should download backup with blob response type", async () => {
      const mockBlob = new Blob(["test"], { type: "application/zip" });
      request.mockResolvedValue(mockBlob);

      const result = await downloadBackup(1, 1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/1/download",
        method: "get",
        responseType: "blob",
      });
      expect(result).toEqual(mockBlob);
    });
  });

  describe("verifyBackup", () => {
    it("should verify backup integrity", async () => {
      const mockData = { valid: true, message: "Backup is valid", details: {} };
      request.mockResolvedValue(mockData);

      const result = await verifyBackup(1, 1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/1/verify",
        method: "post",
      });
      expect(result).toEqual(mockData);
    });

    it("should return invalid for corrupted backup", async () => {
      const mockData = { valid: false, message: "Backup file is corrupted" };
      request.mockResolvedValue(mockData);

      const result = await verifyBackup(1, 1);

      expect(result.valid).toBe(false);
    });
  });

  describe("getBackupStats", () => {
    it("should fetch backup statistics", async () => {
      const mockData = {
        totalCount: 10,
        totalSize: 1073741824,
        lastBackupTime: "2024-01-15T10:30:00Z",
        backupFrequency: { daily: 5, weekly: 3, monthly: 2 },
      };
      request.mockResolvedValue(mockData);

      const result = await getBackupStats(1);

      expect(request).toHaveBeenCalledWith({
        url: "/instances/1/backups/stats",
        method: "get",
      });
      expect(result).toEqual(mockData);
    });
  });
});
