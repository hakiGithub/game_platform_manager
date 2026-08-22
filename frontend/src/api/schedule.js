import request from "@/utils/request";

/**
 * 定时计划模块 API（ADR-0011）
 * 模块路径: /api/schedules
 */

/**
 * 分页查询定时计划
 * @param {Object} params - 查询参数
 * @param {string} [params.source] - 来源过滤（MAIN/L4D2/...）
 * @param {string} [params.handlerKey] - 处理器 key 过滤
 * @param {boolean} [params.enabled] - 启用状态过滤
 * @param {string} [params.keyword] - 名称模糊搜索
 * @param {number} [params.page=1] - 页码
 * @param {number} [params.size=20] - 每页大小
 * @returns {Promise<{current: number, size: number, total: number, pages: number, records: Array}>}
 */
export function getScheduleList(params) {
  return request({
    url: "/schedules",
    method: "get",
    params,
  });
}

/**
 * 获取计划详情
 * @param {string} id - 计划ID
 * @returns {Promise<Object>}
 */
export function getScheduleDetail(id) {
  return request({
    url: `/schedules/${id}`,
    method: "get",
  });
}

/**
 * 获取已注册的定时任务处理器列表
 * @returns {Promise<Array<{source: string, taskType: string, displayName: string}>>}
 */
export function getScheduleHandlers() {
  return request({
    url: "/schedules/handlers",
    method: "get",
  });
}

/**
 * 创建定时计划
 * @param {Object} data - 创建请求
 * @param {string} data.source - 来源（MAIN 或插件 gameCode 大写）
 * @param {string} data.name - 计划名称
 * @param {string} data.handlerKey - 处理器 key
 * @param {string} data.cron - cron 表达式（Spring 语法 6 位）
 * @param {Object} [data.payload] - payload 模板
 * @param {boolean} [data.enabled] - 是否启用（缺省 true）
 * @returns {Promise<string>} 计划ID
 */
export function createSchedule(data) {
  return request({
    url: "/schedules",
    method: "post",
    data,
  });
}

/**
 * 更新定时计划（name/cron/payload）
 * @param {string} id - 计划ID
 * @param {Object} data - 更新请求
 * @returns {Promise<boolean>}
 */
export function updateSchedule(id, data) {
  return request({
    url: `/schedules/${id}`,
    method: "put",
    data,
  });
}

/**
 * 启用计划
 * @param {string} id - 计划ID
 * @returns {Promise<boolean>}
 */
export function enableSchedule(id) {
  return request({
    url: `/schedules/${id}/enable`,
    method: "post",
  });
}

/**
 * 禁用计划（只停未来触发，进行中的 run 跑完）
 * @param {string} id - 计划ID
 * @returns {Promise<boolean>}
 */
export function disableSchedule(id) {
  return request({
    url: `/schedules/${id}/disable`,
    method: "post",
  });
}

/**
 * 删除计划（取消进行中的 run，触发记录保留）
 * @param {string} id - 计划ID
 * @returns {Promise<boolean>}
 */
export function deleteSchedule(id) {
  return request({
    url: `/schedules/${id}`,
    method: "delete",
  });
}

/**
 * 立即手动触发一次
 * @param {string} id - 计划ID
 * @returns {Promise<string>} runId
 */
export function triggerSchedule(id) {
  return request({
    url: `/schedules/${id}/trigger`,
    method: "post",
  });
}

/**
 * 分页查询计划的触发记录
 * @param {string} id - 计划ID
 * @param {Object} [params] - 查询参数
 * @param {string} [params.status] - 状态过滤（RUNNING/SUCCEEDED/FAILED/CANCELLED/SKIPPED）
 * @param {number} [params.page=1] - 页码
 * @param {number} [params.size=20] - 每页大小
 * @returns {Promise<{current: number, size: number, total: number, pages: number, records: Array}>}
 */
export function getScheduleRuns(id, params) {
  return request({
    url: `/schedules/${id}/runs`,
    method: "get",
    params,
  });
}

/**
 * 获取触发记录详情
 * @param {string} runId - 触发记录ID
 * @returns {Promise<Object>}
 */
export function getScheduleRunDetail(runId) {
  return request({
    url: `/schedules/runs/${runId}`,
    method: "get",
  });
}

/**
 * 取消进行中的触发
 * @param {string} runId - 触发记录ID
 * @returns {Promise<boolean>}
 */
export function cancelScheduleRun(runId) {
  return request({
    url: `/schedules/runs/${runId}/cancel`,
    method: "post",
  });
}

/**
 * 获取触发记录的执行日志（时间正序，最多 500 条）
 * @param {string} runId - 触发记录ID
 * @returns {Promise<Array>}
 */
export function getScheduleRunLogs(runId) {
  return request({
    url: `/schedules/runs/${runId}/logs`,
    method: "get",
  });
}
