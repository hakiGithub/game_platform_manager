package com.gameplatform.service;

import com.gameplatform.plugin.task.TaskQuery;
import com.gameplatform.plugin.task.TaskVO;
import com.gameplatform.plugin.task.TaskLog;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.vo.TaskStatsVO;
import com.gameplatform.vo.TaskTypeVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务管理服务接口（仅主应用 core 使用，ADR-025）
 *
 * <p>由 {@link com.gameplatform.controller.TaskController} 调用，
 * 提供取消/重试/删除/统计等管理操作。鉴权由 Controller 层负责。
 *
 * <p>插件不应直接使用此接口，应使用 {@link com.gameplatform.plugin.task.TaskService}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface TaskAdminService {

    /**
     * 取消任意任务（管理员或提交者本人）
     *
     * <p>取消语义（ADR 取消流程）：
     * <ul>
     *   <li>PENDING 状态：直接乐观更新为 CANCELLED，释放互斥键</li>
     *   <li>RUNNING 状态：设置 ctx.cancelled=true，等待 Handler 自检退出</li>
     *   <li>终态：抛 {@link com.gameplatform.task.exception.TaskNotCancellableException}</li>
     * </ul>
     *
     * @param taskId 任务ID
     * @return true 表示取消请求已接受（实际终止由 Handler 决定）
     */
    boolean cancel(String taskId);

    /**
     * 按来源批量取消任务（插件卸载时调用，ADR-013）
     *
     * <p>协作式取消，等待最多 10s 让 Handler 优雅退出。
     *
     * @param source 任务来源
     * @return 已取消的任务数量
     */
    int cancelBySource(String source);

    /**
     * 重试任务（ADR-027 不修改原任务状态）
     *
     * <p>检查：
     * <ul>
     *   <li>原任务必须为 FAILED 或 CANCELLED</li>
     *   <li>{@code Handler.isRetryable()} 返回 true</li>
     *   <li>{@code retryCount < Handler.getMaxRetryCount()}</li>
     * </ul>
     *
     * @param taskId 原任务ID
     * @return 新任务ID
     */
    String retry(String taskId);

    /**
     * 删除任务记录（软删除）
     *
     * <p>仅允许终态任务删除。
     *
     * @param taskId 任务ID
     * @return true 表示删除成功
     */
    boolean delete(String taskId);

    /**
     * 按来源物理删除所有任务记录与日志（插件卸载时调用，ADR-013）
     *
     * <p>不走逻辑删除字段，直接 DELETE。
     *
     * @param source 任务来源
     * @return 已删除的任务记录数量
     */
    int purgeBySource(String source);

    /**
     * 获取已注册的任务类型列表
     *
     * @return 任务类型列表
     */
    List<TaskTypeVO> listTypes();

    /**
     * 获取任务详情（含 Handler 元信息：maxRetryCount/retryable/taskTypeName）
     *
     * @param taskId 任务ID
     * @return 任务详情 VO
     */
    TaskVO getTask(String taskId);

    /**
     * 分页查询任务
     *
     * @param query 查询条件
     * @return 分页结果
     */
    PageResult<TaskVO> listTasks(TaskQuery query);

    /**
     * 获取任务日志（按时间正序，最多 500 条，ADR-037）
     *
     * @param taskId 任务ID
     * @return 日志列表
     */
    List<TaskLog> getTaskLogs(String taskId);

    /**
     * 增量查询日志（id > afterId，按时间正序，ADR-037）
     *
     * @param taskId  任务ID
     * @param afterId 上次最后一条日志的ID（为 null 时返回最近 100 条）
     * @return 日志列表
     */
    List<TaskLog> getTaskLogsAfter(String taskId, String afterId);

    /**
     * 任务统计（多维聚合，ADR-015）
     *
     * @param startTime 时间范围起始（含），为 null 不限
     * @param endTime   时间范围结束（含），为 null 不限
     * @return 统计结果
     */
    TaskStatsVO getStats(LocalDateTime startTime, LocalDateTime endTime);
}
