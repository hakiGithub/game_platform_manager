import request from "@/utils/request";

/**
 * 主机管理模块 API
 * 模块路径: /api/hosts
 */

/**
 * 获取主机列表（分页）
 * @param {Object} params - 查询参数
 * @param {number} [params.current=1] - 当前页码
 * @param {number} [params.size=10] - 每页大小
 * @param {string} [params.name] - 主机名称（模糊查询）
 * @param {string} [params.ip] - IP地址（模糊查询）
 * @param {number} [params.status] - 状态：0-离线，1-在线
 * @returns {Promise<{current: number, size: number, total: number, pages: number, records: Array}>}
 */
export function getHostList(params) {
  return request({
    url: "/hosts",
    method: "get",
    params,
  });
}

/**
 * 获取主机详情
 * @param {number} id - 主机ID
 * @returns {Promise<Object>}
 */
export function getHostDetail(id) {
  return request({
    url: `/hosts/${id}`,
    method: "get",
  });
}

/**
 * 新增主机
 * @param {Object} data - 主机数据
 * @param {string} data.name - 主机名称
 * @param {string} data.ip - IP地址
 * @param {number} [data.sshPort=22] - SSH端口
 * @param {string} data.sshUsername - SSH用户名
 * @param {string} [data.sshPassword] - SSH密码（与sshPrivateKey二选一）
 * @param {string} [data.sshPrivateKey] - SSH私钥（与sshPassword二选一）
 * @param {string} [data.tags] - 标签(JSON数组格式)
 * @param {string} [data.remark] - 备注
 * @returns {Promise<{id: number}>}
 */
export function createHost(data) {
  return request({
    url: "/hosts",
    method: "post",
    data,
  });
}

/**
 * 更新主机
 * @param {number} id - 主机ID
 * @param {Object} data - 更新数据
 * @param {string} [data.name] - 主机名称
 * @param {number} [data.sshPort] - SSH端口
 * @param {string} [data.sshUsername] - SSH用户名
 * @param {string} [data.sshPassword] - SSH密码
 * @param {string} [data.sshPrivateKey] - SSH私钥
 * @param {string} [data.tags] - 标签(JSON数组格式)
 * @param {string} [data.remark] - 备注
 * @returns {Promise<null>}
 */
export function updateHost(id, data) {
  return request({
    url: `/hosts/${id}`,
    method: "put",
    data,
  });
}

/**
 * 删除主机
 * @param {number} id - 主机ID
 * @returns {Promise<null>}
 */
export function deleteHost(id) {
  return request({
    url: `/hosts/${id}`,
    method: "delete",
  });
}

/**
 * 测试主机连接
 * @param {number} id - 主机ID
 * @returns {Promise<{success: boolean, message: string, latency: number}>}
 */
export function testHostConnection(id) {
  return request({
    url: `/hosts/${id}/test`,
    method: "post",
  });
}

/**
 * 用表单参数测试主机连接（新增主机保存前的强制连接测试）
 * @param {{ip:string, sshPort:number, username:string, password?:string, privateKey?:string}} data - 连接参数
 * @returns {Promise<{connected: boolean, message: string, testTime: number}>}
 */
export function testHostConnectionByParams(data) {
  return request({
    url: "/hosts/test-connection",
    method: "post",
    data,
  });
}

/**
 * 获取主机状态
 * @param {number} id - 主机ID
 * @returns {Promise<{status: number, cpuUsage: number, memoryUsage: number, diskUsage: number, uptime: number, loadAverage: string}>}
 */
export function getHostStatus(id) {
  return request({
    url: `/hosts/${id}/status`,
    method: "get",
  });
}

/**
 * 扫描端口占用
 * @param {number} id - 主机ID
 * @param {Object} [params] - 查询参数
 * @param {number} [params.startPort=1] - 起始端口
 * @param {number} [params.endPort=65535] - 结束端口
 * @returns {Promise<{ports: Array<{port: number, protocol: string, service: string, pid: number}>}>}
 */
export function scanPorts(id, params) {
  return request({
    url: `/hosts/${id}/ports`,
    method: "get",
    params,
  });
}

/**
 * 获取主机资源使用情况
 * @param {number} id - 主机ID
 * @returns {Promise<{cpu: Object, memory: Object, disk: Object, network: Object}>}
 */
export function getHostResources(id) {
  return request({
    url: `/hosts/${id}/resources`,
    method: "get",
  });
}

/**
 * 预检 hosts 刷新
 * @param {number} id - 主机ID
 * @returns {Promise<{hostLanIp: string, hostname: string, domainsToRefresh: string[], sudoAvailable: boolean, needsSudoPassword: boolean}>}
 */
export function previewHostsRefresh(id) {
  return request({
    url: `/hosts/${id}/hosts-preview`,
    method: "get",
  });
}

/**
 * 执行 hosts 刷新
 * @param {number} id - 主机ID
 * @param {string|null} sudoPassword - sudo密码（免密sudo时为null）
 * @param {string[]|null} selectedDomains - 选中的待改域名（null/空 表示刷新全部；非空 只刷新指定域名，用于跳过广告屏蔽条目）
 * @returns {Promise<{success: boolean, errorMessage: string, backupPath: string, refreshedDomains: string[], hostLanIp: string}>}
 */
export function refreshHosts(id, sudoPassword, selectedDomains = null) {
  return request({
    url: `/hosts/${id}/hosts-refresh`,
    method: "post",
    data: { sudoPassword, selectedDomains },
  });
}

// ==================== Steam302 主机加速 ====================

/**
 * 查询 Steam302 部署状态
 * @param {number} id - 主机ID
 * @returns {Promise<{phase: string, containerStatus: string, image: string, hostsEntries: number, proxiedDomains: number, certTrusted: boolean, message: string}>}
 */
export function getSteam302Status(id) {
  return request({
    url: `/hosts/${id}/steam302/status`,
    method: "get",
  });
}

/**
 * 提交 Steam302 安装任务（异步）
 * @param {number} id - 主机ID
 * @returns {Promise<string>} 任务ID
 */
export function installSteam302(id) {
  return request({
    url: `/hosts/${id}/steam302/install`,
    method: "post",
  });
}

/**
 * 启动 Steam302 容器
 * @param {number} id - 主机ID
 * @returns {Promise<null>}
 */
export function startSteam302(id) {
  return request({
    url: `/hosts/${id}/steam302/start`,
    method: "post",
  });
}

/**
 * 停止 Steam302 容器
 * @param {number} id - 主机ID
 * @returns {Promise<null>}
 */
export function stopSteam302(id) {
  return request({
    url: `/hosts/${id}/steam302/stop`,
    method: "post",
  });
}

/**
 * 读取 Steam302 服务开关配置（S302.ini [Setting]）
 * @param {number} id - 主机ID
 * @returns {Promise<Object<string, string>>} 有序键值表
 */
export function getSteam302Config(id) {
  return request({
    url: `/hosts/${id}/steam302/config`,
    method: "get",
  });
}

/**
 * 保存 Steam302 服务开关配置（保存后需重启生效）
 * @param {number} id - 主机ID
 * @param {Object<string, string>} values - 待修改的键值子集
 * @returns {Promise<null>}
 */
export function saveSteam302Config(id, values) {
  return request({
    url: `/hosts/${id}/steam302/config`,
    method: "put",
    data: values,
  });
}

/**
 * 切换 Steam302 容器共享加速
 * 开启后 hosts 劫持条目指向宿主机 LAN IP（bridge 容器与宿主机均可走代理），立即重写生效
 * @param {number} id - 主机ID
 * @param {boolean} enabled - 是否开启
 * @returns {Promise<null>}
 */
export function setSteam302ContainerShare(id, enabled) {
  return request({
    url: `/hosts/${id}/steam302/container-share`,
    method: "put",
    data: { enabled },
  });
}

/**
 * 查询主机环境工具状态（ADR-0021）
 * @param {number} hostId - 主机ID
 * @returns {Promise<{tools: Array<{tool: string, displayName: string, installed: boolean, installable: boolean}>, packageManager: string, sudoNopasswd: boolean, docker: boolean}>}
 */
export function getHostTools(hostId) {
  return request({
    url: `/hosts/${hostId}/tools`,
    method: "get",
  });
}

/**
 * 安装主机环境工具（白名单，同步执行，超时约 2 分钟）
 * @param {number} hostId - 主机ID
 * @param {string} tool - 工具名（unzip/unrar/7z/xz/bzip2/tar/gzip/curl/wget/rsync）
 * @returns {Promise<string>} 安装输出
 */
export function installHostTool(hostId, tool) {
  return request({
    url: `/hosts/${hostId}/tools/${tool}/install`,
    method: "post",
    timeout: 150000,
  });
}
