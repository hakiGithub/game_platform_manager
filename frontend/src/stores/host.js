import { defineStore } from "pinia";
import { ref, computed } from "vue";
import {
  getHostList,
  getHostDetail,
  getHostStatus,
  getHostResources,
} from "@/api/host";

export const useHostStore = defineStore("host", () => {
  // ========== 状态 ==========
  const hostList = ref([]);
  const currentHost = ref(null);
  const hostStatus = ref({});
  const hostResources = ref({});
  const loading = ref(false);
  const pagination = ref({
    current: 1,
    size: 10,
    total: 0,
    pages: 0,
  });

  // ========== 计算属性 ==========
  const onlineHosts = computed(() =>
    hostList.value.filter((h) => h.status === 1),
  );

  const offlineHosts = computed(() =>
    hostList.value.filter((h) => h.status === 0),
  );

  const onlineCount = computed(() => onlineHosts.value.length);
  const totalCount = computed(() => hostList.value.length);

  // ========== Actions ==========

  /**
   * 获取主机列表
   * @param {Object} params - 查询参数
   * @returns {Promise<Object>}
   */
  async function fetchHostList(params = {}) {
    loading.value = true;
    try {
      const data = await getHostList({
        current: pagination.value.current,
        size: pagination.value.size,
        ...params,
      });

      hostList.value = data.records || [];
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
   * 获取主机详情
   * @param {number} id - 主机ID
   * @returns {Promise<Object>}
   */
  async function fetchHostDetail(id) {
    try {
      const data = await getHostDetail(id);
      currentHost.value = data;
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 获取主机状态
   * @param {number} id - 主机ID
   * @returns {Promise<Object>}
   */
  async function fetchHostStatus(id) {
    try {
      const data = await getHostStatus(id);
      hostStatus.value[id] = data;
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 获取主机资源使用情况
   * @param {number} id - 主机ID
   * @returns {Promise<Object>}
   */
  async function fetchHostResources(id) {
    try {
      const data = await getHostResources(id);
      hostResources.value[id] = data;
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 批量获取在线主机资源
   * @returns {Promise<void>}
   */
  async function fetchOnlineHostsResources() {
    const onlineIds = onlineHosts.value.map((h) => h.id);

    for (const id of onlineIds) {
      try {
        await fetchHostResources(id);
      } catch (error) {
        console.error(`Failed to fetch resources for host ${id}:`, error);
      }
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
   * 清除当前主机
   */
  function clearCurrentHost() {
    currentHost.value = null;
  }

  /**
   * 清除所有状态
   */
  function clearAll() {
    hostList.value = [];
    currentHost.value = null;
    hostStatus.value = {};
    hostResources.value = {};
    pagination.value = {
      current: 1,
      size: 10,
      total: 0,
      pages: 0,
    };
  }

  /**
   * 更新主机列表中的主机状态
   * @param {number} id - 主机ID
   * @param {Object} status - 状态数据
   */
  function updateHostStatusInList(id, status) {
    const index = hostList.value.findIndex((h) => h.id === id);
    if (index !== -1) {
      hostList.value[index] = { ...hostList.value[index], ...status };
    }
  }

  return {
    // 状态
    hostList,
    currentHost,
    hostStatus,
    hostResources,
    loading,
    pagination,

    // 计算属性
    onlineHosts,
    offlineHosts,
    onlineCount,
    totalCount,

    // Actions
    fetchHostList,
    fetchHostDetail,
    fetchHostStatus,
    fetchHostResources,
    fetchOnlineHostsResources,
    updatePagination,
    clearCurrentHost,
    clearAll,
    updateHostStatusInList,
  };
});
