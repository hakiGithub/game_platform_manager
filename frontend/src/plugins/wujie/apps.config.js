/**
 * 无界子应用配置
 * 注册所有以 Wujie 方式加载的插件前端应用
 */

/**
 * 插件子应用列表
 * @type {Array<{
 *   name: string,
 *   url: string,
 *   activated: boolean,
 *   gameCode?: string,
 *   props?: Record<string, unknown>
 * }>}
 */
export const pluginApps = [
  {
    // 子应用唯一标识，对应 Wujie 的 name
    name: 'l4d2-plugin',
    // L4D2 插件前端入口地址，开发环境可通过 .env 覆盖
    url: import.meta.env.VITE_L4D2_PLUGIN_URL || 'http://localhost:9000',
    // 是否启用该插件子应用
    activated: true,
    // 关联的游戏代码
    gameCode: 'l4d2'
  }
]

/**
 * 根据子应用名称获取配置
 * @param {string} name - 子应用 name
 * @returns {typeof pluginApps[number] | undefined}
 */
export function getPluginAppConfig(name) {
  return pluginApps.find(app => app.name === name)
}

/**
 * 根据游戏代码获取配置
 * @param {string} gameCode - 游戏代码
 * @returns {typeof pluginApps[number] | undefined}
 */
export function getPluginAppConfigByGameCode(gameCode) {
  return pluginApps.find(app => app.gameCode === gameCode)
}

/**
 * 生成 Wujie 子应用 name
 * 保证同一游戏插件在不同实例间复用同一个无界沙箱实例
 * @param {string} gameCode - 游戏代码
 * @returns {string}
 */
export function generateWujieAppName(gameCode) {
  return `plugin-${gameCode}`
}
