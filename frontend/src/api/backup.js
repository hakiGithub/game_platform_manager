import request from "@/utils/request";

/**
 * 备份管理模块 API
 * 模块路径: /api/instances/{instanceId}/backups
 */

/**
 * 获取备份列表
 * @param {number} instanceId - 实例ID
 * @param {Object} [params] - 查询参数
 * @param {number} [params.current=1] - 当前页码
 * @param {number} [params.size=10] - 每页大小
 * @param {string} [params.type] - 备份类型: database-数据库, files-文件
 * @param {string} [params.status] - 备份状态: pending-等待中, running-备份中, completed-成功, failed-失败
 * @returns {Promise<{current: number, size: number, total: number, pages: number, records: Array}>}
 */
export function getBackupList(instanceId, params) {
  return request({
    url: `/instances/${instanceId}/backups`,
    method: "get",
    params,
  });
}

/**
 * 获取备份详情
 * @param {number} instanceId - 实例ID
 * @param {number} backupId - 备份ID
 * @returns {Promise<Object>}
 */
export function getBackupDetail(instanceId, backupId) {
  return request({
    url: `/instances/${instanceId}/backups/${backupId}`,
    method: "get",
  });
}

/**
 * 创建数据库备份
 * @param {number} instanceId - 实例ID
 * @param {Object} data - 备份参数
 * @param {string} data.name - 备份名称
 * @param {string} [data.description] - 备份描述
 * @returns {Promise<{id: number, name: string, status: string}>}
 */
export function createDatabaseBackup(instanceId, data) {
  return request({
    url: `/instances/${instanceId}/backups/database`,
    method: "post",
    data,
  });
}

/**
 * 创建文件备份
 * @param {number} instanceId - 实例ID
 * @param {Object} data - 备份参数
 * @param {string} data.name - 备份名称
 * @param {string} [data.description] - 备份描述
 * @param {string[]} [data.includePaths] - 包含的文件路径列表
 * @param {string[]} [data.excludePaths] - 排除的文件路径列表
 * @returns {Promise<{id: number, name: string, status: string}>}
 */
export function createFilesBackup(instanceId, data) {
  return request({
    url: `/instances/${instanceId}/backups/files`,
    method: "post",
    data,
  });
}

/**
 * 获取备份进度
 * @param {number} instanceId - 实例ID
 * @param {number} backupId - 备份ID
 * @returns {Promise<{progress: number, status: string, message: string, currentStep: string, completed: boolean}>}
 */
export function getBackupProgress(instanceId, backupId) {
  return request({
    url: `/instances/${instanceId}/backups/${backupId}/progress`,
    method: "get",
  });
}

/**
 * 取消备份
 * @param {number} instanceId - 实例ID
 * @param {number} backupId - 备份ID
 * @returns {Promise<{success: boolean, message: string}>}
 */
export function cancelBackup(instanceId, backupId) {
  return request({
    url: `/instances/${instanceId}/backups/${backupId}/cancel`,
    method: "post",
  });
}

/**
 * 还原备份
 * @param {number} instanceId - 实例ID
 * @param {number} backupId - 备份ID
 * @param {Object} [options] - 还原选项
 * @param {boolean} [options.restoreDatabase=true] - 是否还原数据库
 * @param {boolean} [options.restoreFiles=true] - 是否还原文件
 * @param {string} [options.targetPath] - 自定义还原路径
 * @returns {Promise<{restoreId: string, status: string, message: string}>}
 */
export function restoreBackup(instanceId, backupId, options = {}) {
  return request({
    url: `/instances/${instanceId}/backups/${backupId}/restore`,
    method: "post",
    data: options,
  });
}

/**
 * 获取还原进度
 * @param {number} instanceId - 实例ID
 * @param {string} restoreId - 还原任务ID
 * @returns {Promise<{progress: number, status: string, message: string, currentStep: string, completed: boolean}>}
 */
export function getRestoreProgress(instanceId, restoreId) {
  return request({
    url: `/instances/${instanceId}/restore-progress/${restoreId}`,
    method: "get",
  });
}

/**
 * 删除备份
 * @param {number} instanceId - 实例ID
 * @param {number} backupId - 备份ID
 * @returns {Promise<{success: boolean, message: string}>}
 */
export function deleteBackup(instanceId, backupId) {
  return request({
    url: `/instances/${instanceId}/backups/${backupId}`,
    method: "delete",
  });
}

/**
 * 批量删除备份
 * @param {number} instanceId - 实例ID
 * @param {number[]} backupIds - 备份ID列表
 * @returns {Promise<{success: boolean, deletedCount: number, message: string}>}
 */
export function batchDeleteBackups(instanceId, backupIds) {
  return request({
    url: `/instances/${instanceId}/backups/batch`,
    method: "delete",
    data: { backupIds },
  });
}

/**
 * 下载备份文件
 * @param {number} instanceId - 实例ID
 * @param {number} backupId - 备份ID
 * @returns {Promise<Blob>}
 */
export function downloadBackup(instanceId, backupId) {
  return request({
    url: `/instances/${instanceId}/backups/${backupId}/download`,
    method: "get",
    responseType: "blob",
  });
}

/**
 * 验证备份
 * @param {number} instanceId - 实例ID
 * @param {number} backupId - 备份ID
 * @returns {Promise<{valid: boolean, message: string, details: Object}>}
 */
export function verifyBackup(instanceId, backupId) {
  return request({
    url: `/instances/${instanceId}/backups/${backupId}/verify`,
    method: "post",
  });
}

/**
 * 获取备份统计信息
 * @param {number} instanceId - 实例ID
 * @returns {Promise<{totalCount: number, totalSize: number, lastBackupTime: string, backupFrequency: Object}>}
 */
export function getBackupStats(instanceId) {
  return request({
    url: `/instances/${instanceId}/backups/stats`,
    method: "get",
  });
}
