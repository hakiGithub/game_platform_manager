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
