import { defineStore } from "pinia";
import { ref, computed } from "vue";
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

/**
 * 备份管理 Store
 * 用于管理游戏实例的备份相关状态
 */
export const useBackupStore = defineStore("backup", () => {
  // ========== 状态 ==========
  const backupList = ref([]);
  const currentBackup = ref(null);
  const backupStats = ref({
    totalCount: 0,
    totalSize: 0,
    lastBackupTime: null,
    backupFrequency: {},
  });
  const loading = ref(false);
  const pagination = ref({
    current: 1,
    size: 10,
    total: 0,
    pages: 0,
  });

  // 备份进度状态
  const activeBackupProgress = ref({
    backupId: null,
    progress: 0,
    status: "",
    message: "",
    currentStep: "",
    completed: false,
    polling: false,
  });

  // 还原进度状态
  const activeRestoreProgress = ref({
    restoreId: null,
    progress: 0,
    status: "",
    message: "",
    currentStep: "",
    completed: false,
    polling: false,
  });

  // 轮询定时器
  let backupPollTimer = null;
  let restorePollTimer = null;

  // ========== 计算属性 ==========
  const hasActiveBackup = computed(() => activeBackupProgress.value.polling);
  const hasActiveRestore = computed(() => activeRestoreProgress.value.polling);
  const isBackupRunning = computed(
    () => activeBackupProgress.value.status === "running",
  );
  const isRestoreRunning = computed(
    () => activeRestoreProgress.value.status === "running",
  );

  // 按状态分组的备份
  const backupsByStatus = computed(() => {
    const groups = {
      pending: [],
      running: [],
      completed: [],
      failed: [],
    };
    backupList.value.forEach((backup) => {
      if (groups[backup.status]) {
        groups[backup.status].push(backup);
      }
    });
    return groups;
  });

  // 数据库备份列表
  const databaseBackups = computed(() =>
    backupList.value.filter((b) => b.type === "database"),
  );

  // 文件备份列表
  const fileBackups = computed(() =>
    backupList.value.filter((b) => b.type === "files"),
  );

  // ========== Actions ==========

  /**
   * 获取备份列表
   * @param {number} instanceId - 实例ID
   * @param {Object} params - 查询参数
   * @returns {Promise<Object>}
   */
  async function fetchBackupList(instanceId, params = {}) {
    loading.value = true;
    try {
      const data = await getBackupList(instanceId, {
        current: pagination.value.current,
        size: pagination.value.size,
        ...params,
      });

      backupList.value = data.records || [];
      pagination.value = {
        current: data.current || 1,
        size: data.size || 10,
        total: data.total || 0,
        pages: data.pages || 0,
      };

      return data;
    } catch (error) {
      throw error;
    } finally {
      loading.value = false;
    }
  }

  /**
   * 获取备份详情
   * @param {number} instanceId - 实例ID
   * @param {number} backupId - 备份ID
   * @returns {Promise<Object>}
   */
  async function fetchBackupDetail(instanceId, backupId) {
    try {
      const data = await getBackupDetail(instanceId, backupId);
      currentBackup.value = data;
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 获取备份统计信息
   * @param {number} instanceId - 实例ID
   * @returns {Promise<Object>}
   */
  async function fetchBackupStats(instanceId) {
    try {
      const data = await getBackupStats(instanceId);
      backupStats.value = data;
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 创建数据库备份
   * @param {number} instanceId - 实例ID
   * @param {Object} data - 备份参数
   * @returns {Promise<Object>}
   */
  async function createDatabase(instanceId, data) {
    try {
      const result = await createDatabaseBackup(instanceId, data);
      // 开始轮询进度
      if (result.id) {
        startBackupProgressPolling(instanceId, result.id);
      }
      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 创建文件备份
   * @param {number} instanceId - 实例ID
   * @param {Object} data - 备份参数
   * @returns {Promise<Object>}
   */
  async function createFiles(instanceId, data) {
    try {
      const result = await createFilesBackup(instanceId, data);
      // 开始轮询进度
      if (result.id) {
        startBackupProgressPolling(instanceId, result.id);
      }
      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 开始备份进度轮询
   * @param {number} instanceId - 实例ID
   * @param {number} backupId - 备份ID
   * @param {Function} onProgress - 进度回调
   * @param {Function} onComplete - 完成回调
   */
  function startBackupProgressPolling(
    instanceId,
    backupId,
    onProgress,
    onComplete,
  ) {
    // 清除之前的轮询
    stopBackupProgressPolling();

    activeBackupProgress.value = {
      backupId,
      progress: 0,
      status: "running",
      message: "开始备份...",
      currentStep: "",
      completed: false,
      polling: true,
    };

    const poll = async () => {
      try {
        const progress = await getBackupProgress(instanceId, backupId);

        activeBackupProgress.value = {
          ...activeBackupProgress.value,
          progress: progress.progress || 0,
          status: progress.status,
          message: progress.message || "",
          currentStep: progress.currentStep || "",
          completed: progress.completed,
        };

        if (onProgress) {
          onProgress(activeBackupProgress.value);
        }

        if (progress.completed) {
          stopBackupProgressPolling();
          if (onComplete) {
            onComplete(progress);
          }
          return;
        }

        // 继续轮询
        backupPollTimer = setTimeout(() => poll(), 2000);
      } catch (error) {
        console.error("Backup progress polling error:", error);
        activeBackupProgress.value.message = "获取进度失败";
        backupPollTimer = setTimeout(() => poll(), 5000); // 出错后延长轮询间隔
      }
    };

    poll();
  }

  /**
   * 停止备份进度轮询
   */
  function stopBackupProgressPolling() {
    if (backupPollTimer) {
      clearTimeout(backupPollTimer);
      backupPollTimer = null;
    }
    activeBackupProgress.value.polling = false;
  }

  /**
   * 取消备份
   * @param {number} instanceId - 实例ID
   * @param {number} backupId - 备份ID
   * @returns {Promise<Object>}
   */
  async function cancel(instanceId, backupId) {
    try {
      const result = await cancelBackup(instanceId, backupId);
      stopBackupProgressPolling();
      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 还原备份
   * @param {number} instanceId - 实例ID
   * @param {number} backupId - 备份ID
   * @param {Object} options - 还原选项
   * @returns {Promise<Object>}
   */
  async function restore(instanceId, backupId, options = {}) {
    try {
      const result = await restoreBackup(instanceId, backupId, options);
      // 开始轮询还原进度
      if (result.restoreId) {
        startRestoreProgressPolling(instanceId, result.restoreId);
      }
      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 开始还原进度轮询
   * @param {number} instanceId - 实例ID
   * @param {string} restoreId - 还原任务ID
   * @param {Function} onProgress - 进度回调
   * @param {Function} onComplete - 完成回调
   */
  function startRestoreProgressPolling(
    instanceId,
    restoreId,
    onProgress,
    onComplete,
  ) {
    // 清除之前的轮询
    stopRestoreProgressPolling();

    activeRestoreProgress.value = {
      restoreId,
      progress: 0,
      status: "running",
      message: "开始还原...",
      currentStep: "",
      completed: false,
      polling: true,
    };

    const poll = async () => {
      try {
        const progress = await getRestoreProgress(instanceId, restoreId);

        activeRestoreProgress.value = {
          ...activeRestoreProgress.value,
          progress: progress.progress || 0,
          status: progress.status,
          message: progress.message || "",
          currentStep: progress.currentStep || "",
          completed: progress.completed,
        };

        if (onProgress) {
          onProgress(activeRestoreProgress.value);
        }

        if (progress.completed) {
          stopRestoreProgressPolling();
          if (onComplete) {
            onComplete(progress);
          }
          return;
        }

        // 继续轮询
        restorePollTimer = setTimeout(() => poll(), 2000);
      } catch (error) {
        console.error("Restore progress polling error:", error);
        activeRestoreProgress.value.message = "获取进度失败";
        restorePollTimer = setTimeout(() => poll(), 5000);
      }
    };

    poll();
  }

  /**
   * 停止还原进度轮询
   */
  function stopRestoreProgressPolling() {
    if (restorePollTimer) {
      clearTimeout(restorePollTimer);
      restorePollTimer = null;
    }
    activeRestoreProgress.value.polling = false;
  }

  /**
   * 删除备份
   * @param {number} instanceId - 实例ID
   * @param {number} backupId - 备份ID
   * @returns {Promise<Object>}
   */
  async function remove(instanceId, backupId) {
    try {
      const result = await deleteBackup(instanceId, backupId);
      // 从列表中移除
      const index = backupList.value.findIndex((b) => b.id === backupId);
      if (index !== -1) {
        backupList.value.splice(index, 1);
        pagination.value.total--;
      }
      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 批量删除备份
   * @param {number} instanceId - 实例ID
   * @param {number[]} backupIds - 备份ID列表
   * @returns {Promise<Object>}
   */
  async function batchRemove(instanceId, backupIds) {
    try {
      const result = await batchDeleteBackups(instanceId, backupIds);
      // 从列表中移除
      backupList.value = backupList.value.filter(
        (b) => !backupIds.includes(b.id),
      );
      pagination.value.total -= backupIds.length;
      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 下载备份
   * @param {number} instanceId - 实例ID
   * @param {number} backupId - 备份ID
   * @param {string} fileName - 文件名
   */
  async function download(instanceId, backupId, fileName) {
    try {
      const blob = await downloadBackup(instanceId, backupId);
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = fileName || `backup-${backupId}.zip`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      return true;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 验证备份
   * @param {number} instanceId - 实例ID
   * @param {number} backupId - 备份ID
   * @returns {Promise<Object>}
   */
  async function verify(instanceId, backupId) {
    try {
      const result = await verifyBackup(instanceId, backupId);
      // 更新当前备份的验证状态
      if (currentBackup.value && currentBackup.value.id === backupId) {
        currentBackup.value.verified = result.valid;
        currentBackup.value.verifyMessage = result.message;
      }
      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 更新分页参数
   * @param {Object} params - 分页参数
   */
  function updatePagination(params) {
    pagination.value = { ...pagination.value, ...params };
  }

  /**
   * 清除当前备份
   */
  function clearCurrentBackup() {
    currentBackup.value = null;
  }

  /**
   * 清除所有状态
   */
  function clearAll() {
    backupList.value = [];
    currentBackup.value = null;
    backupStats.value = {
      totalCount: 0,
      totalSize: 0,
      lastBackupTime: null,
      backupFrequency: {},
    };
    pagination.value = {
      current: 1,
      size: 10,
      total: 0,
      pages: 0,
    };
    stopBackupProgressPolling();
    stopRestoreProgressPolling();
    activeBackupProgress.value = {
      backupId: null,
      progress: 0,
      status: "",
      message: "",
      currentStep: "",
      completed: false,
      polling: false,
    };
    activeRestoreProgress.value = {
      restoreId: null,
      progress: 0,
      status: "",
      message: "",
      currentStep: "",
      completed: false,
      polling: false,
    };
  }

  /**
   * 获取备份状态文本
   * @param {string} status - 状态码
   * @returns {string}
   */
  function getBackupStatusText(status) {
    const statusMap = {
      pending: "等待中",
      running: "备份中",
      completed: "成功",
      failed: "失败",
      cancelled: "已取消",
    };
    return statusMap[status] || status;
  }

  /**
   * 获取备份状态标签类型
   * @param {string} status - 状态码
   * @returns {string}
   */
  function getBackupStatusType(status) {
    const typeMap = {
      pending: "info",
      running: "warning",
      completed: "success",
      failed: "danger",
      cancelled: "info",
    };
    return typeMap[status] || "info";
  }

  /**
   * 获取备份类型文本
   * @param {string} type - 类型码
   * @returns {string}
   */
  function getBackupTypeText(type) {
    const typeMap = {
      database: "数据库",
      files: "文件",
      full: "完整备份",
    };
    return typeMap[type] || type;
  }

  return {
    // 状态
    backupList,
    currentBackup,
    backupStats,
    loading,
    pagination,
    activeBackupProgress,
    activeRestoreProgress,

    // 计算属性
    hasActiveBackup,
    hasActiveRestore,
    isBackupRunning,
    isRestoreRunning,
    backupsByStatus,
    databaseBackups,
    fileBackups,

    // Actions
    fetchBackupList,
    fetchBackupDetail,
    fetchBackupStats,
    createDatabase,
    createFiles,
    startBackupProgressPolling,
    stopBackupProgressPolling,
    cancel,
    restore,
    startRestoreProgressPolling,
    stopRestoreProgressPolling,
    remove,
    batchRemove,
    download,
    verify,
    updatePagination,
    clearCurrentBackup,
    clearAll,
    getBackupStatusText,
    getBackupStatusType,
    getBackupTypeText,
  };
});
