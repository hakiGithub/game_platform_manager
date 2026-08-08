import request from "@/utils/request";

/**
 * 任务中心模块 API
 * 模块路径: /api/tasks
 */

/**
 * 分页查询任务列表
 * @param {Object} params - 查询参数
 * @param {string} [params.source] - 来源过滤（MAIN/L4D2/...）
 * @param {string} [params.taskType] - 任务类型过滤
 * @param {string} [params.status] - 状态过滤（PENDING/RUNNING/COMPLETED/FAILED/CANCELLED）
 * @param {string} [params.scopeKey] - 作用域键过滤
 * @param {string} [params.submitter] - 提交者过滤
 * @param {string} [params.startTime] - 起始时间
 * @param {string} [params.endTime] - 结束时间
 * @param {string} [params.keyword] - 关键字搜索
 * @param {number} [params.page=1] - 页码
 * @param {number} [params.size=20] - 每页大小
 * @returns {Promise<{current: number, size: number, total: number, pages: number, records: Array}>}
 */
export function getTaskList(params) {
  return request({
    url: "/tasks",
    method: "get",
    params,
  });
}

/**
 * 获取任务详情
 * @param {string} taskId - 任务ID
 * @returns {Promise<Object>}
 */
export function getTaskDetail(taskId) {
  return request({
    url: `/tasks/${taskId}`,
    method: "get",
  });
}

/**
 * 获取任务日志（增量查询）
 * @param {string} taskId - 任务ID
 * @param {string} [afterId] - 上次最后一条日志ID（首次拉取不传）
 * @returns {Promise<Array>}
 */
export function getTaskLogs(taskId, afterId) {
  return request({
    url: `/tasks/${taskId}/logs`,
    method: "get",
    params: afterId ? { afterId } : {},
  });
}

/**
 * 取消任务
 * @param {string} taskId - 任务ID
 * @returns {Promise<boolean>}
 */
export function cancelTask(taskId) {
  return request({
    url: `/tasks/${taskId}/cancel`,
    method: "post",
  });
}

/**
 * 重试任务
 * @param {string} taskId - 原任务ID
 * @returns {Promise<string>} 新任务ID
 */
export function retryTask(taskId) {
  return request({
    url: `/tasks/${taskId}/retry`,
    method: "post",
  });
}

/**
 * 删除任务（软删除）
 * @param {string} taskId - 任务ID
 * @returns {Promise<boolean>}
 */
export function deleteTask(taskId) {
  return request({
    url: `/tasks/${taskId}`,
    method: "delete",
  });
}

/**
 * 获取已注册的任务类型列表
 * @returns {Promise<Array<{source: string, taskType: string, displayName: string}>>}
 */
export function getTaskTypes() {
  return request({
    url: "/tasks/types",
    method: "get",
  });
}

/**
 * 任务统计
 * @param {Object} [params] - 查询参数
 * @param {string} [params.startTime] - 起始时间
 * @param {string} [params.endTime] - 结束时间
 * @returns {Promise<{statusCounts: Object, sourceCounts: Object, typeCounts: Object, total: number}>}
 */
export function getTaskStats(params) {
  return request({
    url: "/tasks/stats",
    method: "get",
    params,
  });
}
