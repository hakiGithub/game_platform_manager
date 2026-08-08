package com.gameplatform.plugin.task;

import com.gameplatform.common.result.PageResult;

import java.util.List;

/**
 * 任务服务接口（注入插件子容器）
 *
 * <p>按 ADR-025 拆分，仅提供提交、查询、取消自己来源任务的接口；
 * 管理（重试、删除、统计等）通过 TaskAdminService 由主应用 Controller 调用。
 *
 * <p>插件提交任务示例：
 * <pre>{@code
 * @RequiredArgsConstructor
 * public class MapCenterService {
 *     private final TaskService taskService;
 *
 *     public String triggerCrawl(String type) {
 *         return taskService.submit(TaskSubmitRequest.builder()
 *                 .taskType("crawl")
 *                 .source("L4D2")
 *                 .scopeType("GLOBAL")
 *                 .payload(Map.of("crawlType", type))
 *                 .build());
 *     }
 * }
 * }</pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface TaskService {

    /**
     * 提交任务
     *
     * @param request 提交请求
     * @return 任务ID
     */
    String submit(TaskSubmitRequest request);

    /**
     * 获取任务详情
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
     * 获取任务日志（按时间正序，最多 500 条）
     *
     * @param taskId 任务ID
     * @return 日志列表
     */
    List<TaskLog> getTaskLogs(String taskId);

    /**
     * 取消本来源提交的任务（仅限当前 source）
     *
     * <p>插件无法取消其他来源的任务；source 由 TaskService 实现内部根据调用上下文推断
     * （插件子容器注册时绑定的 pluginSource）。
     *
     * @param taskId 任务ID
     * @return true 表示取消请求已接受（任务实际终止由 Handler 决定）
     */
    boolean cancelMyOwn(String taskId);
}
