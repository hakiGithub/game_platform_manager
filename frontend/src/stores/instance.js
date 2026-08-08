import { defineStore } from "pinia";
import { ref, computed } from "vue";
import {
  getInstanceList,
  getInstanceDetail,
  getInstanceStatus,
  getInstanceLogs,
  startInstance,
  stopInstance,
  restartInstance,
} from "@/api/instance";

export const useInstanceStore = defineStore("instance", () => {
  // ========== 状态 ==========
  const instanceList = ref([]);
  const currentInstance = ref(null);
  const instanceStatus = ref({});
  const instanceLogs = ref({});
  const loading = ref(false);
  const pagination = ref({
    current: 1,
    size: 10,
    total: 0,
    pages: 0,
  });

  // ========== 计算属性 ==========
  const runningInstances = computed(() =>
    instanceList.value.filter((i) => i.status === 1),
  );

  const stoppedInstances = computed(() =>
    instanceList.value.filter((i) => i.status === 0),
  );

  const errorInstances = computed(() =>
    instanceList.value.filter((i) => i.status === 2),
  );

  const runningCount = computed(() => runningInstances.value.length);
  const stoppedCount = computed(() => stoppedInstances.value.length);
  const errorCount = computed(() => errorInstances.value.length);
  const totalCount = computed(() => instanceList.value.length);

  // ========== Actions ==========

  /**
   * 获取实例列表
   * @param {Object} params - 查询参数
   * @returns {Promise<Object>}
   */
  async function fetchInstanceList(params = {}) {
    loading.value = true;
    try {
      const data = await getInstanceList({
        current: pagination.value.current,
        size: pagination.value.size,
        ...params,
      });

      instanceList.value = data.records || [];
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
   * 获取实例详情
   * @param {number} id - 实例ID
   * @returns {Promise<Object>}
   */
  async function fetchInstanceDetail(id) {
    try {
      const data = await getInstanceDetail(id);
      currentInstance.value = data;
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 获取实例状态
   * @param {number} id - 实例ID
   * @returns {Promise<Object>}
   */
  async function fetchInstanceStatus(id) {
    try {
      const data = await getInstanceStatus(id);
      instanceStatus.value[id] = data;
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 获取实例日志
   * @param {number} id - 实例ID
   * @param {Object} params - 查询参数
   * @returns {Promise<Object>}
   */
  async function fetchInstanceLogs(id, params = {}) {
    try {
      const data = await getInstanceLogs(id, params);
      instanceLogs.value[id] = data.logs || [];
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 启动实例
   * @param {number} id - 实例ID
   * @returns {Promise<Object>}
   */
  async function start(id) {
    try {
      const data = await startInstance(id);
      // 更新实例状态
      updateInstanceStatusInList(id, { status: 1, processId: data.processId });
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 停止实例
   * @param {number} id - 实例ID
   * @param {Object} [options] - 选项
   * @param {boolean} [options.force=false] - 是否强制停止
   * @returns {Promise<Object>}
   */
  async function stop(id, options = {}) {
    try {
      const data = await stopInstance(id, options);
      // 更新实例状态
      updateInstanceStatusInList(id, { status: 0, processId: null });
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 重启实例
   * @param {number} id - 实例ID
   * @returns {Promise<Object>}
   */
  async function restart(id) {
    try {
      const data = await restartInstance(id);
      // 更新实例状态
      updateInstanceStatusInList(id, { status: 1, processId: data.processId });
      return data;
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
   * 清除当前实例
   */
  function clearCurrentInstance() {
    currentInstance.value = null;
  }

  /**
   * 清除所有状态
   */
  function clearAll() {
    instanceList.value = [];
    currentInstance.value = null;
    instanceStatus.value = {};
    instanceLogs.value = {};
    pagination.value = {
      current: 1,
      size: 10,
      total: 0,
      pages: 0,
    };
  }

  /**
   * 更新实例列表中的实例状态
   * @param {number} id - 实例ID
   * @param {Object} status - 状态数据
   */
  function updateInstanceStatusInList(id, status) {
    const index = instanceList.value.findIndex((i) => i.id === id);
    if (index !== -1) {
      instanceList.value[index] = { ...instanceList.value[index], ...status };
    }

    // 同时更新当前实例
    if (currentInstance.value && currentInstance.value.id === id) {
      currentInstance.value = { ...currentInstance.value, ...status };
    }
  }

  /**
   * 获取实例状态文本
   * @param {number} status - 状态码
   * @returns {string}
   */
  function getStatusText(status) {
    const statusMap = {
      0: "已停止",
      1: "运行中",
      2: "异常",
      3: "部署中",
      4: "卸载中",
    };
    return statusMap[status] || "未知";
  }

  /**
   * 获取实例状态标签类型
   * @param {number} status - 状态码
   * @returns {string}
   */
  function getStatusType(status) {
    const typeMap = {
      0: "info",
      1: "success",
      2: "danger",
      3: "warning",
      4: "warning",
    };
    return typeMap[status] || "info";
  }

  return {
    // 状态
    instanceList,
    currentInstance,
    instanceStatus,
    instanceLogs,
    loading,
    pagination,

    // 计算属性
    runningInstances,
    stoppedInstances,
    errorInstances,
    runningCount,
    stoppedCount,
    errorCount,
    totalCount,

    // Actions
    fetchInstanceList,
    fetchInstanceDetail,
    fetchInstanceStatus,
    fetchInstanceLogs,
    start,
    stop,
    restart,
    updatePagination,
    clearCurrentInstance,
    clearAll,
    updateInstanceStatusInList,
    getStatusText,
    getStatusType,
  };
});
