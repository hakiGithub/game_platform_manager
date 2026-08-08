import request from '@/utils/request'

/**
 * 系统设置模块 API
 * 模块路径: /api/system
 */

/**
 * 获取系统设置
 * @returns {Promise<{settings: Array}>}
 */
export function getSystemSettings() {
  return request({
    url: '/system/settings',
    method: 'get'
  })
}

/**
 * 更新系统设置
 * @param {Object} data - 设置数据
 * @param {string} data.type - 配置类型：'platform' | 'ssh' | 'docker'
 * @returns {Promise<null>}
 */
export function updateSystemSettings(data) {
  return request({
    url: '/system/settings',
    method: 'put',
    data
  })
}

/**
 * 修改当前用户密码
 * @param {Object} data - 密码数据
 * @param {string} data.oldPassword - 旧密码
 * @param {string} data.newPassword - 新密码
 * @returns {Promise<null>}
 */
export function changePassword(data) {
  return request({
    url: '/system/password',
    method: 'put',
    data
  })
}

/**
 * 获取操作日志（分页）
 * @param {Object} params - 查询参数
 * @param {number} [params.current=1] - 当前页码
 * @param {number} [params.size=10] - 每页大小
 * @param {string} [params.operatorName] - 操作人用户名
 * @param {string} [params.operationModule] - 操作模块
 * @param {string} [params.operationType] - 操作类型
 * @param {number} [params.responseStatus] - 响应状态：0-失败，1-成功
 * @param {string} [params.startTime] - 开始时间
 * @param {string} [params.endTime] - 结束时间
 * @returns {Promise<{current: number, size: number, total: number, pages: number, records: Array}>}
 */
export function getOperationLogs(params) {
  return request({
    url: '/system/logs',
    method: 'get',
    params
  })
}

/**
 * 导出操作日志
 * @param {Object} params - 导出查询参数
 * @returns {Promise<Blob>}
 */
export function exportLogs(params) {
  return request({
    url: '/system/logs/export',
    method: 'get',
    params,
    responseType: 'blob',
    silent: true
  })
}

/**
 * 健康检查
 * @returns {Promise<{status: string, timestamp: string, version: string}>}
 */
export function healthCheck() {
  return request({
    url: '/system/health',
    method: 'get'
  })
}

/**
 * 获取系统信息
 * @returns {Promise<{name: string, version: string, description: string, javaVersion: string, osName: string, osVersion: string}>}
 */
export function getSystemInfo() {
  return request({
    url: '/system/info',
    method: 'get'
  })
}

/**
 * 获取系统统计信息
 * @returns {Promise<{hosts: Object, instances: Object, plugins: Object}>}
 */
export function getStatistics() {
  return request({
    url: '/system/statistics',
    method: 'get'
  })
}
