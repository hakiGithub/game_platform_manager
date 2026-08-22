package com.gameplatform.plugin.schedule;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.plugin.task.TaskLog;

import java.util.List;

/**
 * 定时计划服务接口（注入插件子容器，ADR-0011 D5）
 *
 * <p>实现按插件绑定 source（插件子容器注册时由 PluginSpringContextFactory
 * 传入 pluginId + source），所有操作强制本来源隔离——插件无法创建/修改/
 * 删除/触发其他来源的计划，查询亦仅返回本来源。
 *
 * <p>插件使用示例：
 * <pre>{@code
 * @RequiredArgsConstructor
 * public class MapCenterService {
 *     private final ScheduleService scheduleService;
 *
 *     public String createDailyCrawl() {
 *         return scheduleService.create(ScheduleCreateRequest.builder() // 若为 builder 风格
 *                 ...
 *                 .build());
 *     }
 * }
 * }</pre>
 *
 * <p>声明式默认计划请走 {@link ScheduledTaskDeclarationExtension}；
 * 本接口面向运行时动态创建。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ScheduleService {

    /**
     * 创建计划（source/pluginId 由实现自动填充为本插件）
     *
     * @param request 创建请求
     * @return 计划ID
     */
    String create(ScheduleCreateRequest request);

    /**
     * 更新计划（仅本来源；name/cron/payload）
     *
     * @param id      计划ID
     * @param request 更新请求
     */
    void update(String id, ScheduleUpdateRequest request);

    /**
     * 启用计划（仅本来源）
     */
    void enable(String id);

    /**
     * 禁用计划（仅本来源；只停未来触发，进行中的 run 跑完）
     */
    void disable(String id);

    /**
     * 删除计划（仅本来源；取消进行中的 run，删除为逻辑删除——
     * 若是声明式计划，插件重载后不会复活）
     */
    void delete(String id);

    /**
     * 立即手动触发一次（仅本来源；产生 MANUAL 来源 run，
     * 遇上一轮仍在执行则记 SKIPPED）
     *
     * @param id 计划ID
     * @return runId
     */
    String trigger(String id);

    /**
     * 获取计划详情（仅本来源）
     */
    ScheduleVO get(String id);

    /**
     * 分页查询本来源计划
     */
    PageResult<ScheduleVO> list(ScheduleQuery query);

    /**
     * 分页查询计划的触发记录（仅本来源计划）
     */
    PageResult<ScheduleRunVO> listRuns(ScheduleRunQuery query);

    /**
     * 获取触发记录的执行日志（按时间正序，最多 500 条；仅本来源计划）
     *
     * @param runId 触发记录ID
     * @return 日志列表
     */
    List<TaskLog> getRunLogs(String runId);
}
