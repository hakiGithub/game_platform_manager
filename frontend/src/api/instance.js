import request from "@/utils/request";

/**
 * 游戏实例模块 API
 * 模块路径: /api/instances
 */

/**
 * 获取实例列表（分页）
 * @param {Object} params - 查询参数
 * @param {number} [params.current=1] - 当前页码
 * @param {number} [params.size=10] - 每页大小
 * @param {string} [params.name] - 实例名称（模糊查询）
 * @param {number} [params.hostId] - 主机ID
 * @param {string} [params.gameCode] - 游戏编码
 * @param {number} [params.status] - 运行状态：0-已停止，1-运行中，2-异常
 * @returns {Promise<{current: number, size: number, total: number, pages: number, records: Array}>}
 */
export function getInstanceList(params) {
  return request({
    url: "/instances",
    method: "get",
    params,
  });
}

/**
 * 根据游戏ID获取实例列表
 * @param {number} gameId - 游戏ID
 * @returns {Promise<Array>}
 */
export function getInstancesByGameId(gameId) {
  return request({
    url: `/instances/game/${gameId}`,
    method: "get",
  });
}

/**
 * 获取实例详情
 * @param {number} id - 实例ID
 * @returns {Promise<Object>}
 */
export function getInstanceDetail(id) {
  return request({
    url: `/instances/${id}`,
    method: "get",
  });
}

/**
 * 获取实例动态资源数据（CPU/内存/运行时长等）
 * <p>
 * 该接口会触发后端 SSH/Docker 调用，响应较慢（数百毫秒至数秒）。
 * 调用方应异步处理，不要阻塞页面首屏渲染。
 * @param {number|string} id - 实例ID
 * @returns {Promise<Object>} 动态数据，包含 cpuUsage/memoryUsage/memoryUsageText/uptime/onlinePlayers 等字段
 */
export function getInstanceMetrics(id) {
  return request({
    url: `/instances/${id}/metrics`,
    method: "get",
  });
}

/**
 * 创建实例
 * @param {Object} data - 实例数据
 * @param {string} data.name - 实例名称
 * @param {string} data.gameType - 游戏类型（游戏编码）
 * @param {string} [data.gameVersion] - 游戏版本
 * @param {number} data.hostId - 主机ID
 * @param {string} data.deployPath - 部署路径
 * @param {number} data.port - 端口号
 * @param {string} [data.startArgs] - 启动参数
 * @param {number} [data.autoRestart=0] - 自动重启：0-否，1-是
 * @param {string} [data.remark] - 备注
 * @returns {Promise<{id: number, deployTaskId: string}>}
 */
export function createInstance(data) {
  return request({
    url: "/instances",
    method: "post",
    data,
  });
}

/**
 * 更新实例配置
 * @param {number} id - 实例ID
 * @param {Object} data - 更新数据
 * @param {string} [data.name] - 实例名称
 * @param {number} [data.port] - 端口号
 * @param {string} [data.startArgs] - 启动参数
 * @param {number} [data.autoRestart] - 自动重启
 * @param {string} [data.remark] - 备注
 * @returns {Promise<null>}
 */
export function updateInstance(id, data) {
  return request({
    url: `/instances/${id}`,
    method: "put",
    data,
  });
}

/**
 * 删除实例
 * @param {number} id - 实例ID
 * @param {Object} [params] - 参数
 * @param {boolean} [params.deleteFiles=false] - 是否删除文件
 * @returns {Promise<null>}
 */
export function deleteInstance(id, params) {
  return request({
    url: `/instances/${id}`,
    method: "delete",
    params,
  });
}

/**
 * 启动实例
 * @param {number} id - 实例ID
 * @returns {Promise<{success: boolean, processId: number, message: string}>}
 */
export function startInstance(id) {
  return request({
    url: `/instances/${id}/start`,
    method: "post",
  });
}

/**
 * 停止实例
 * @param {number} id - 实例ID
 * @param {Object} [data] - 参数
 * @param {boolean} [data.force=false] - 是否强制停止
 * @returns {Promise<{success: boolean, message: string}>}
 */
export function stopInstance(id, data) {
  return request({
    url: `/instances/${id}/stop`,
    method: "post",
    data,
  });
}

/**
 * 重启实例
 * @param {number} id - 实例ID
 * @returns {Promise<{success: boolean, processId: number, message: string}>}
 */
export function restartInstance(id) {
  return request({
    url: `/instances/${id}/restart`,
    method: "post",
  });
}

/**
 * 获取实例状态
 * @param {number} id - 实例ID
 * @returns {Promise<{status: number, processId: number, uptime: number}>}
 */
export function getInstanceStatus(id) {
  return request({
    url: `/instances/${id}/status`,
    method: "get",
  });
}

/**
 * 获取实例日志
 * @param {number} id - 实例ID
 * @param {Object} [params] - 查询参数
 * @param {number} [params.lines=100] - 日志行数
 * @param {string} [params.keyword] - 关键词过滤
 * @param {string} [params.startTime] - 开始时间
 * @param {string} [params.endTime] - 结束时间
 * @returns {Promise<{logs: Array, total: number}>}
 */
export function getInstanceLogs(id, params) {
  return request({
    url: `/instances/${id}/logs`,
    method: "get",
    params,
  });
}

/**
 * 获取实例配置
 * @param {number} id - 实例ID
 * @param {Object} [params] - 参数
 * @param {string} [params.configFile] - 配置文件名
 * @returns {Promise<{fileName: string, content: string, lastModified: string, size: number}>}
 */
export function getInstanceConfig(id, params) {
  return request({
    url: `/instances/${id}/config`,
    method: "get",
    params,
  });
}

/**
 * 更新实例配置
 * @param {number} id - 实例ID
 * @param {Object} data - 配置数据
 * @param {string} [data.configFile] - 配置文件名
 * @param {string} data.content - 配置文件内容
 * @param {boolean} [data.restart=false] - 是否重启实例使配置生效
 * @returns {Promise<null>}
 */
export function updateInstanceConfig(id, data) {
  return request({
    url: `/instances/${id}/config`,
    method: "put",
    data,
  });
}

/**
 * 获取实例文件列表
 * @param {number} id - 实例ID
 * @param {Object} [params] - 参数
 * @param {string} [params.path] - 目录路径
 * @param {boolean} [params.showHidden=false] - 是否显示隐藏文件
 * @returns {Promise<{currentPath: string, files: Array}>}
 */
export function getInstanceFiles(id, params) {
  return request({
    url: `/instances/${id}/files`,
    method: "get",
    params,
  });
}

/**
 * 下载文件
 * @param {number} id - 实例ID
 * @param {string} filePath - 文件路径
 * @returns {Promise<Blob>}
 */
export function downloadFile(id, filePath) {
  return request({
    url: `/instances/${id}/files/download`,
    method: "get",
    params: { path: filePath },
    responseType: "blob",
  });
}

/**
 * 上传文件
 * @param {number} id - 实例ID
 * @param {FormData} formData - 表单数据
 * @param {File} formData.file - 上传的文件
 * @param {string} [formData.path] - 目标目录路径
 * @param {boolean} [formData.overwrite=false] - 是否覆盖已存在的文件
 * @returns {Promise<{fileName: string, filePath: string, size: number}>}
 */
export function uploadFile(id, formData) {
  return request({
    url: `/instances/${id}/files/upload`,
    method: "post",
    headers: {
      "Content-Type": "multipart/form-data",
    },
    data: formData,
  });
}

/**
 * 删除文件
 * @param {number} id - 实例ID
 * @param {string} filePath - 文件路径
 * @returns {Promise<null>}
 */
export function deleteFile(id, filePath) {
  return request({
    url: `/instances/${id}/files`,
    method: "delete",
    data: { path: filePath },
  });
}

/**
 * 创建目录
 * @param {number} id - 实例ID
 * @param {string} path - 目录路径
 * @returns {Promise<null>}
 */
export function createDirectory(id, path) {
  return request({
    url: `/instances/${id}/files/directory`,
    method: "post",
    data: { path },
  });
}

/**
 * 读取文件内容
 * @param {number} id - 实例ID
 * @param {string} filePath - 文件路径
 * @returns {Promise<{content: string, encoding: string}>}
 */
export function readFile(id, filePath) {
  return request({
    url: `/instances/${id}/files/content`,
    method: "get",
    params: { path: filePath },
  });
}

/**
 * 保存文件内容
 * @param {number} id - 实例ID
 * @param {string} filePath - 文件路径
 * @param {string} content - 文件内容
 * @returns {Promise<null>}
 */
export function saveFile(id, filePath, content) {
  return request({
    url: `/instances/${id}/files/content`,
    method: "put",
    data: { path: filePath, content },
  });
}

/**
 * 获取备份列表
 * @param {number} id - 实例ID
 * @returns {Promise<Array>}
 */
export function getBackups(id) {
  return request({
    url: `/instances/${id}/backups`,
    method: "get",
  });
}

/**
 * 创建备份
 * @param {number} id - 实例ID
 * @param {Object} [data] - 备份参数
 * @param {string} [data.name] - 备份名称
 * @param {string} [data.description] - 备份描述
 * @returns {Promise<{id: number, name: string}>}
 */
export function createBackup(id, data) {
  return request({
    url: `/instances/${id}/backups`,
    method: "post",
    data,
  });
}

/**
 * 还原备份
 * @param {number} id - 实例ID
 * @param {number} backupId - 备份ID
 * @returns {Promise<null>}
 */
export function restoreBackup(id, backupId) {
  return request({
    url: `/instances/${id}/backups/${backupId}/restore`,
    method: "post",
  });
}

/**
 * 删除备份
 * @param {number} id - 实例ID
 * @param {number} backupId - 备份ID
 * @returns {Promise<null>}
 */
export function deleteBackup(id, backupId) {
  return request({
    url: `/instances/${id}/backups/${backupId}`,
    method: "delete",
  });
}

/**
 * 发送控制台命令
 * @param {number} id - 实例ID
 * @param {string} command - 命令内容
 * @returns {Promise<{output: string}>}
 */
export function sendConsoleCommand(id, command) {
  return request({
    url: `/instances/${id}/console`,
    method: "post",
    data: { command },
  });
}

/**
 * 环境校验
 * @param {Object} data - 校验参数
 * @param {number} data.hostId - 主机ID
 * @param {number} data.port - 端口号
 * @param {string} data.deployMethod - 部署方式
 * @returns {Promise<{checks: Array, passed: boolean}>}
 */
export function checkEnvironment(data) {
  return request({
    url: "/instances/check-environment",
    method: "post",
    data,
  });
}

/**
 * 检查端口占用
 * @param {number} hostId - 主机ID
 * @param {number} port - 端口号
 * @returns {Promise<{available: boolean, usedBy: string}>}
 */
export function checkPort(hostId, port) {
  return request({
    url: `/hosts/${hostId}/check-port`,
    method: "get",
    params: { port },
  });
}

/**
 * 获取部署进度
 * @param {number|string} instanceId - 实例ID（即部署任务ID）
 * @returns {Promise<{progress: number, status: string, statusText: string, logs: Array, completed: boolean, success: boolean, error: string}>}
 */
export function getDeployProgress(instanceId) {
  return request({
    url: `/instances/${instanceId}/deploy-progress`,
    method: "get",
  });
}

/**
 * 重试部署
 * @param {number} id - 实例ID
 * @returns {Promise<null>}
 */
export function retryDeploy(id) {
  return request({
    url: `/instances/${id}/retry-deploy`,
    method: "post",
  });
}
