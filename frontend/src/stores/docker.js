/**
 * Docker 状态管理
 * 管理容器、镜像、关联等状态
 */
import { defineStore } from "pinia";
import { ref, computed } from "vue";
import {
  getContainerList,
  getContainerDetail,
  getContainerStats,
  getImageList,
  getLinkList,
  autoLinkContainers,
  createLink,
  deleteLink,
} from "@/api/docker";

export const useDockerStore = defineStore("docker", () => {
  // ==================== 容器状态 ====================
  const containers = ref([]);
  const currentContainer = ref(null);
  const containerStats = ref({});
  const containerLoading = ref(false);
  const containerDetailLoading = ref(false);

  // ==================== 镜像状态 ====================
  const images = ref([]);
  const imagesLoading = ref(false);

  // ==================== 关联状态 ====================
  const links = ref([]);
  const linksLoading = ref(false);

  // ==================== 当前选中的主机 ====================
  const currentHostId = ref(null);

  // ==================== 计算属性 ====================

  // 运行中的容器
  const runningContainers = computed(() =>
    containers.value.filter((c) => c.status === "running"),
  );

  // 已停止的容器
  const stoppedContainers = computed(() =>
    containers.value.filter((c) => c.status === "stopped"),
  );

  // 已关联的容器
  const linkedContainers = computed(() =>
    containers.value.filter((c) => c.isLinked),
  );

  // 未关联的容器
  const unlinkedContainers = computed(() =>
    containers.value.filter((c) => !c.isLinked),
  );

  // 统计数据
  const containerStats_summary = computed(() => ({
    total: containers.value.length,
    running: runningContainers.value.length,
    stopped: stoppedContainers.value.length,
    linked: linkedContainers.value.length,
    unlinked: unlinkedContainers.value.length,
  }));

  // 悬空镜像
  const danglingImages = computed(() =>
    images.value.filter((i) => i.isDangling),
  );

  // ==================== 容器 Actions ====================

  /**
   * 获取容器列表
   * @param {number} hostId - 主机ID
   * @param {Object} params - 查询参数
   * @returns {Promise<Object>}
   */
  async function fetchContainers(hostId, params = {}) {
    if (!hostId) return;

    currentHostId.value = hostId;
    containerLoading.value = true;

    try {
      const data = await getContainerList(hostId, params);
      containers.value = data.containers || [];
      return data;
    } catch (error) {
      throw error;
    } finally {
      containerLoading.value = false;
    }
  }

  /**
   * 获取容器详情
   * @param {number} hostId - 主机ID
   * @param {string} containerId - 容器ID
   * @returns {Promise<Object>}
   */
  async function fetchContainerDetail(hostId, containerId) {
    containerDetailLoading.value = true;

    try {
      const data = await getContainerDetail(hostId, containerId);
      currentContainer.value = data;
      return data;
    } catch (error) {
      throw error;
    } finally {
      containerDetailLoading.value = false;
    }
  }

  /**
   * 获取容器资源统计
   * @param {number} hostId - 主机ID
   * @param {string} containerId - 容器ID
   * @returns {Promise<Object>}
   */
  async function fetchContainerStats(hostId, containerId) {
    try {
      const data = await getContainerStats(hostId, containerId);
      containerStats.value[containerId] = data;
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 更新容器列表中的容器状态
   * @param {string} containerId - 容器ID
   * @param {Object} updates - 更新数据
   */
  function updateContainerInList(containerId, updates) {
    const index = containers.value.findIndex(
      (c) => c.containerId === containerId,
    );
    if (index !== -1) {
      containers.value[index] = { ...containers.value[index], ...updates };
    }
  }

  /**
   * 从列表中移除容器
   * @param {string} containerId - 容器ID
   */
  function removeContainerFromList(containerId) {
    const index = containers.value.findIndex(
      (c) => c.containerId === containerId,
    );
    if (index !== -1) {
      containers.value.splice(index, 1);
    }
  }

  // ==================== 镜像 Actions ====================

  /**
   * 获取镜像列表
   * @param {number} hostId - 主机ID
   * @param {Object} params - 查询参数
   * @returns {Promise<Object>}
   */
  async function fetchImages(hostId, params = {}) {
    if (!hostId) return;

    imagesLoading.value = true;

    try {
      const data = await getImageList(hostId, params);
      images.value = data.images || [];
      return data;
    } catch (error) {
      throw error;
    } finally {
      imagesLoading.value = false;
    }
  }

  // ==================== 关联 Actions ====================

  /**
   * 获取关联列表
   * @param {Object} params - 查询参数
   * @returns {Promise<Object>}
   */
  async function fetchLinks(params = {}) {
    linksLoading.value = true;

    try {
      const data = await getLinkList(params);
      links.value = data.records || [];
      return data;
    } catch (error) {
      throw error;
    } finally {
      linksLoading.value = false;
    }
  }

  /**
   * 执行自动关联
   * @param {number} hostId - 主机ID
   * @returns {Promise<Object>}
   */
  async function executeAutoLink(hostId) {
    try {
      const data = await autoLinkContainers(hostId);
      // 刷新容器列表以更新关联状态
      await fetchContainers(hostId);
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 创建容器关联
   * @param {Object} linkData - 关联数据
   * @returns {Promise<Object>}
   */
  async function createContainerLink(linkData) {
    try {
      const data = await createLink(linkData);
      // 更新容器列表中的关联状态
      updateContainerInList(linkData.containerId, {
        isLinked: true,
        linkedInstanceId: linkData.instanceId,
        linkedInstanceName: linkData.instanceName,
      });
      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 删除容器关联
   * @param {number} linkId - 关联ID
   * @param {string} containerId - 容器ID
   * @returns {Promise<Object>}
   */
  async function removeContainerLink(linkId, containerId) {
    try {
      const data = await deleteLink(linkId);
      // 更新容器列表中的关联状态
      updateContainerInList(containerId, {
        isLinked: false,
        linkedInstanceId: null,
        linkedInstanceName: null,
      });
      return data;
    } catch (error) {
      throw error;
    }
  }

  // ==================== 工具方法 ====================

  /**
   * 获取容器状态类型（用于 Element Plus Tag）
   * @param {string} status - 容器状态
   * @returns {string}
   */
  function getContainerStatusType(status) {
    const types = {
      running: "success",
      stopped: "info",
      paused: "warning",
      restarting: "primary",
    };
    return types[status] || "info";
  }

  /**
   * 获取容器状态文本
   * @param {string} status - 容器状态
   * @returns {string}
   */
  function getContainerStatusText(status) {
    const texts = {
      running: "运行中",
      stopped: "已停止",
      paused: "已暂停",
      restarting: "重启中",
    };
    return texts[status] || status;
  }

  /**
   * 获取健康状态类型
   * @param {string} status - 健康状态
   * @returns {string}
   */
  function getHealthStatusType(status) {
    const types = {
      healthy: "success",
      unhealthy: "danger",
      starting: "warning",
      none: "info",
    };
    return types[status] || "info";
  }

  /**
   * 获取健康状态文本
   * @param {string} status - 健康状态
   * @returns {string}
   */
  function getHealthStatusText(status) {
    const texts = {
      healthy: "健康",
      unhealthy: "不健康",
      starting: "启动中",
      none: "未配置",
    };
    return texts[status] || status;
  }

  /**
   * 格式化文件大小
   * @param {number} bytes - 字节数
   * @returns {string}
   */
  function formatFileSize(bytes) {
    if (!bytes || bytes === 0) return "-";
    const units = ["B", "KB", "MB", "GB", "TB"];
    let size = bytes;
    let unitIndex = 0;
    while (size >= 1024 && unitIndex < units.length - 1) {
      size /= 1024;
      unitIndex++;
    }
    return `${size.toFixed(2)} ${units[unitIndex]}`;
  }

  /**
   * 清除所有状态
   */
  function clearAll() {
    containers.value = [];
    currentContainer.value = null;
    containerStats.value = {};
    images.value = [];
    links.value = [];
    currentHostId.value = null;
  }

  /**
   * 清除当前容器
   */
  function clearCurrentContainer() {
    currentContainer.value = null;
  }

  return {
    // 状态
    containers,
    currentContainer,
    containerStats,
    containerLoading,
    containerDetailLoading,
    images,
    imagesLoading,
    links,
    linksLoading,
    currentHostId,

    // 计算属性
    runningContainers,
    stoppedContainers,
    linkedContainers,
    unlinkedContainers,
    containerStats_summary,
    danglingImages,

    // 容器 Actions
    fetchContainers,
    fetchContainerDetail,
    fetchContainerStats,
    updateContainerInList,
    removeContainerFromList,

    // 镜像 Actions
    fetchImages,

    // 关联 Actions
    fetchLinks,
    executeAutoLink,
    createContainerLink,
    removeContainerLink,

    // 工具方法
    getContainerStatusType,
    getContainerStatusText,
    getHealthStatusType,
    getHealthStatusText,
    formatFileSize,
    clearAll,
    clearCurrentContainer,
  };
});
