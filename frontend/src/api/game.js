import request from "@/utils/request";

/**
 * 游戏元数据模块 API
 * 模块路径: /api/games
 */

/**
 * 获取游戏列表（全量，不分页）
 * @param {Object} [params] - 查询参数
 * @param {string} [params.keyword] - 关键词搜索（游戏名称/编码）
 * @param {number} [params.status] - 状态：0-禁用，1-启用
 * @returns {Promise<Array>}
 */
export function getGameList(params) {
  return request({
    url: "/games/list",
    method: "get",
    params,
  });
}

/**
 * 分页获取游戏列表
 * @param {Object} params - 分页查询参数
 * @param {number} [params.current=1] - 当前页码
 * @param {number} [params.size=10] - 每页条数
 * @param {string} [params.keyword] - 关键词搜索
 * @returns {Promise<{current:number, size:number, total:number, pages:number, records:Array}>}
 */
export function getGamePage(params) {
  return request({
    url: "/games",
    method: "get",
    params,
  });
}

/**
 * 获取游戏详情
 * @param {number} id - 游戏元数据ID
 * @returns {Promise<Object>}
 */
export function getGameDetail(id) {
  return request({
    url: `/games/${id}`,
    method: "get",
  });
}

/**
 * 获取部署配置（变量元信息、compose模板等）
 * @param {number} gameId - 游戏元数据ID
 * @param {string} deployType - 部署类型（docker/linuxgsm/docker-compose）
 * @returns {Promise<Object>} DeployConfigVO
 *   - deployType: string
 *   - composeTemplate: string (docker-compose 类型)
 *   - variables: Array<{name,label,type,defaultValue,required,description,hidden}> (docker-compose 类型)
 *   - namedVolumes: string[] (docker-compose 类型)
 *   - config: Object 完整配置
 */
export function getDeployConfig(gameId, deployType) {
  return request({
    url: `/games/${gameId}/deploy-config/${deployType}`,
    method: "get",
  });
}

/**
 * 新增游戏元数据
 * @param {Object} data - 游戏数据
 * @param {string} data.gameCode - 游戏唯一编码
 * @param {string} data.gameName - 游戏名称
 * @param {string} [data.gameIcon] - 游戏图标URL
 * @param {string} [data.gameDesc] - 游戏描述
 * @param {string} data.supportDeployType - 支持的部署方式(JSON数组)
 * @param {number} [data.defaultPort] - 默认端口
 * @param {string} [data.defaultDependences] - 环境依赖列表(JSON数组)
 * @param {string} [data.configSchema] - 配置表单Schema(JSON格式)
 * @param {string} [data.customOperations] - 自定义操作列表(JSON数组)
 * @param {string} data.metadataFilePath - 元数据文件路径
 * @param {number} [data.status=1] - 状态
 * @returns {Promise<{id: number}>}
 */
export function createGame(data) {
  return request({
    url: "/games",
    method: "post",
    data,
  });
}

/**
 * 更新游戏元数据
 * @param {number} id - 游戏元数据ID
 * @param {Object} data - 更新数据
 * @returns {Promise<null>}
 */
export function updateGame(id, data) {
  return request({
    url: `/games/${id}`,
    method: "put",
    data,
  });
}

/**
 * 删除游戏元数据
 * @param {number} id - 游戏元数据ID
 * @returns {Promise<null>}
 */
export function deleteGame(id) {
  return request({
    url: `/games/${id}`,
    method: "delete",
  });
}
