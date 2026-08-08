import request from "@/utils/request";

/**
 * 插件框架模块 API
 * 模块路径: /api/pf4j
 */

/**
 * 获取所有已加载插件列表
 * @returns {Promise<Array>} PluginStatusVO 列表
 *   - pluginId: string 插件ID
 *   - pluginName: string 插件名称
 *   - version: string 版本
 *   - state: string 状态（STARTED/STOPPED/DISABLED/CREATED）
 *   - stateDesc: string 状态描述
 *   - enabled: boolean 是否启用
 *   - running: boolean 是否运行中
 *   - provider: string 提供者
 *   - description: string 描述
 *   - dependencies: string 依赖
 *   - pluginPath: string 插件路径
 */
export function getPluginList() {
  return request({
    url: "/pf4j/plugins",
    method: "get",
  });
}

/**
 * 获取插件状态
 * @param {string} pluginId - 插件ID
 * @returns {Promise<Object>} PluginStatusVO
 */
export function getPluginStatus(pluginId) {
  return request({
    url: `/pf4j/plugins/${pluginId}/status`,
    method: "get",
  });
}

/**
 * 获取插件清单（通过游戏编码）
 * @param {string} gameCode - 游戏编码
 * @returns {Promise<Object>} PluginManifestVO
 */
export function getPluginManifest(gameCode) {
  return request({
    url: `/pf4j/plugin/${gameCode}/manifest`,
    method: "get",
  });
}

/**
 * 启动插件
 * @param {string} pluginId - 插件ID
 * @returns {Promise<null>}
 */
export function startPlugin(pluginId) {
  return request({
    url: `/pf4j/plugins/${pluginId}/start`,
    method: "post",
  });
}

/**
 * 停止插件
 * @param {string} pluginId - 插件ID
 * @returns {Promise<null>}
 */
export function stopPlugin(pluginId) {
  return request({
    url: `/pf4j/plugins/${pluginId}/stop`,
    method: "post",
  });
}

/**
 * 重新加载插件
 * @param {string} pluginId - 插件ID
 * @returns {Promise<null>}
 */
export function reloadPlugin(pluginId) {
  return request({
    url: `/pf4j/plugins/${pluginId}/reload`,
    method: "post",
  });
}

/**
 * 卸载插件
 * @param {string} pluginId - 插件ID
 * @returns {Promise<null>}
 */
export function unloadPlugin(pluginId) {
  return request({
    url: `/pf4j/plugins/${pluginId}`,
    method: "delete",
  });
}
