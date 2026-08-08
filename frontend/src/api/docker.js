/**
 * Docker 管理 API
 * 提供容器、镜像、文件管理等功能接口
 */
import request from "@/utils/request";

// ==================== 容器管理 API ====================

/**
 * 获取容器列表
 * @param {number} hostId - 主机ID
 * @param {Object} params - 查询参数
 * @returns {Promise<Object>}
 */
export function getContainerList(hostId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers`,
    method: "get",
    params,
  });
}

/**
 * 获取容器详情
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @returns {Promise<Object>}
 */
export function getContainerDetail(hostId, containerId) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}`,
    method: "get",
  });
}

/**
 * 启动容器
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @returns {Promise<Object>}
 */
export function startContainer(hostId, containerId) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/start`,
    method: "post",
  });
}

/**
 * 停止容器
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} data - 停止参数
 * @returns {Promise<Object>}
 */
export function stopContainer(hostId, containerId, data = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/stop`,
    method: "post",
    data,
  });
}

/**
 * 重启容器
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} data - 重启参数
 * @returns {Promise<Object>}
 */
export function restartContainer(hostId, containerId, data = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/restart`,
    method: "post",
    data,
  });
}

/**
 * 删除容器
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} params - 删除参数
 * @returns {Promise<Object>}
 */
export function deleteContainer(hostId, containerId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}`,
    method: "delete",
    params,
  });
}

/**
 * 获取容器资源统计
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @returns {Promise<Object>}
 */
export function getContainerStats(hostId, containerId) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/stats`,
    method: "get",
  });
}

/**
 * 获取容器健康状态
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @returns {Promise<Object>}
 */
export function getContainerHealth(hostId, containerId) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/health`,
    method: "get",
  });
}

// ==================== 容器日志 API ====================

/**
 * 获取容器日志
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} params - 查询参数
 * @returns {Promise<Object>}
 */
export function getContainerLogs(hostId, containerId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/logs`,
    method: "get",
    params,
  });
}

// ==================== 文件管理 API ====================

/**
 * 获取文件列表
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} params - 查询参数
 * @returns {Promise<Object>}
 */
export function getFileList(hostId, containerId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/files`,
    method: "get",
    params,
  });
}

/**
 * 获取文件内容
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} params - 查询参数
 * @returns {Promise<Object>}
 */
export function getFileContent(hostId, containerId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/files/content`,
    method: "get",
    params,
  });
}

/**
 * 更新文件内容
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} data - 文件数据
 * @returns {Promise<Object>}
 */
export function updateFileContent(hostId, containerId, data) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/files/content`,
    method: "put",
    data,
  });
}

/**
 * 删除文件
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} params - 删除参数
 * @returns {Promise<Object>}
 */
export function deleteFile(hostId, containerId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/files`,
    method: "delete",
    params,
  });
}

/**
 * 上传文件
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {FormData} formData - 文件数据
 * @param {Function} onProgress - 上传进度回调
 * @returns {Promise<Object>}
 */
export function uploadFile(hostId, containerId, formData, onProgress) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/files/upload`,
    method: "post",
    data: formData,
    headers: { "Content-Type": "multipart/form-data" },
    onUploadProgress: onProgress,
  });
}

/**
 * 下载文件
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} params - 下载参数
 * @returns {Promise<Blob>}
 */
export function downloadFile(hostId, containerId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/files/download`,
    method: "get",
    params,
    responseType: "blob",
  });
}

/**
 * 拷贝文件
 * @param {number} hostId - 主机ID
 * @param {string} containerId - 容器ID
 * @param {Object} data - 拷贝参数
 * @returns {Promise<Object>}
 */
export function copyFile(hostId, containerId, data) {
  return request({
    url: `/docker/hosts/${hostId}/containers/${containerId}/copy`,
    method: "post",
    data,
  });
}

// ==================== 镜像管理 API ====================

/**
 * 获取镜像列表
 * @param {number} hostId - 主机ID
 * @param {Object} params - 查询参数
 * @returns {Promise<Object>}
 */
export function getImageList(hostId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/images`,
    method: "get",
    params,
  });
}

/**
 * 删除镜像
 * @param {number} hostId - 主机ID
 * @param {string} imageId - 镜像ID
 * @param {Object} params - 删除参数
 * @returns {Promise<Object>}
 */
export function deleteImage(hostId, imageId, params = {}) {
  return request({
    url: `/docker/hosts/${hostId}/images/${imageId}`,
    method: "delete",
    params,
  });
}

/**
 * 清理悬空镜像
 * @param {number} hostId - 主机ID
 * @returns {Promise<Object>}
 */
export function pruneImages(hostId) {
  return request({
    url: `/docker/hosts/${hostId}/images/prune`,
    method: "post",
  });
}

// ==================== 关联管理 API ====================

/**
 * 创建关联
 * @param {Object} data - 关联数据
 * @returns {Promise<Object>}
 */
export function createLink(data) {
  return request({
    url: "/docker/links",
    method: "post",
    data,
  });
}

/**
 * 更新关联
 * @param {number} id - 关联ID
 * @param {Object} data - 更新数据
 * @returns {Promise<Object>}
 */
export function updateLink(id, data) {
  return request({
    url: `/docker/links/${id}`,
    method: "put",
    data,
  });
}

/**
 * 删除关联
 * @param {number} id - 关联ID
 * @returns {Promise<Object>}
 */
export function deleteLink(id) {
  return request({
    url: `/docker/links/${id}`,
    method: "delete",
  });
}

/**
 * 获取关联列表
 * @param {Object} params - 查询参数
 * @returns {Promise<Object>}
 */
export function getLinkList(params = {}) {
  return request({
    url: "/docker/links",
    method: "get",
    params,
  });
}

/**
 * 执行自动关联
 * @param {number} hostId - 主机ID
 * @returns {Promise<Object>}
 */
export function autoLinkContainers(hostId) {
  return request({
    url: "/docker/links/auto",
    method: "post",
    data: { hostId },
  });
}
