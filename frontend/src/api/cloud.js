import request from '@/utils/request'

/**
 * 云盘账号模块 API（ADR-0024）
 * 模块路径: /api/cloud
 */

/** 账号列表（不回显凭证） */
export function listCloudAccounts() {
  return request({ url: '/cloud/accounts', method: 'get' })
}

/**
 * 新增账号（添加即探活）
 * @param {Object} data - { name, providerType, settingsJson, displayName?, remark? }
 */
export function createCloudAccount(data) {
  return request({ url: '/cloud/accounts', method: 'post', data })
}

/** 重贴凭证 */
export function replaceCloudCredential(name, settingsJson) {
  return request({ url: `/cloud/accounts/${name}/credential`, method: 'put', data: { settingsJson } })
}

/** 更新展示信息 */
export function updateCloudAccount(name, data) {
  return request({ url: `/cloud/accounts/${name}`, method: 'put', data })
}

/** 删除账号 */
export function deleteCloudAccount(name) {
  return request({ url: `/cloud/accounts/${name}`, method: 'delete' })
}

/** 探活账号 */
export function verifyCloudAccount(name) {
  return request({ url: `/cloud/accounts/${name}/verify`, method: 'post' })
}

/** 查询配额 */
export function getCloudQuota(name) {
  return request({ url: `/cloud/accounts/${name}/quota`, method: 'get' })
}

/** 可用 Provider 及凭证表单元数据（动态表单用） */
export function listCloudProviders() {
  return request({ url: '/cloud/providers', method: 'get' })
}
