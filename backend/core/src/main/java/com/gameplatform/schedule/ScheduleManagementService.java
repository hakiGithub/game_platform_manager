package com.gameplatform.schedule;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.plugin.schedule.*;
import com.gameplatform.plugin.task.TaskLog;
import com.gameplatform.vo.TaskTypeVO;

import java.util.List;

/**
 * 定时计划管理服务（ADR-0011）
 *
 * <p>面向两类调用方：
 * <ul>
 *   <li>管理侧（REST Controller）：无来源限制的全量操作</li>
 *   <li>插件侧（PluginScheduleServiceAdapter 适配器）：Scoped 系列方法，
 *       强制校验来源归属，插件只能操作自己 source 的计划</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ScheduleManagementService {

    // ==================== 管理侧（REST） ====================

    /**
     * 创建计划（REST 入口；source 由请求指定，须为已注册处理器的来源）
     *
     * @param source   计划来源（MAIN 或插件 gameCode 大写）
     * @param request  创建请求
     * @param operator 操作人（REST 当前用户）
     * @return 计划ID
     */
    String create(String source, ScheduleCreateRequest request, String operator);

    /**
     * 更新计划（name/cron/payload；置 user_modified=1 并重调度）
     */
    void update(String id, ScheduleUpdateRequest request, String operator);

    /**
     * 启用计划并注册调度
     */
    void enable(String id);

    /**
     * 禁用计划（只停未来触发，进行中的 run 跑完）
     */
    void disable(String id);

    /**
     * 删除计划（逻辑删除 = 声明复活墓碑；取消进行中的 run）
     */
    void delete(String id);

    /**
     * 手动立即触发一次（MANUAL 来源 run，遇上一轮仍在执行则 SKIPPED）
     *
     * @return runId
     */
    String trigger(String id);

    /**
     * 取消进行中的 run
     */
    void cancelRun(String runId);

    /**
     * 获取计划详情
     */
    ScheduleVO get(String id);

    /**
     * 分页查询计划（全部来源）
     */
    PageResult<ScheduleVO> list(ScheduleQuery query);

    /**
     * 分页查询计划的触发记录
     */
    PageResult<ScheduleRunVO> listRuns(ScheduleRunQuery query);

    /**
     * 获取触发记录详情
     */
    ScheduleRunVO getRun(String runId);

    /**
     * 获取触发记录的执行日志（时间正序，最多 500 条）
     */
    List<TaskLog> getRunLogs(String runId);

    /**
     * 列出已注册的定时任务处理器（新建计划时选择）
     */
    List<TaskTypeVO> listHandlers();

    // ==================== 插件侧（适配器调用，来源隔离） ====================

    /**
     * 插件创建计划（source/pluginId 由适配器绑定，不可伪造）
     *
     * @param source   插件来源（gameCode 大写）
     * @param pluginId 插件ID
     * @param request  创建请求
     * @return 计划ID
     */
    String createScoped(String source, String pluginId, ScheduleCreateRequest request);

    /**
     * 插件更新自己的计划
     */
    void updateScoped(String id, String source, ScheduleUpdateRequest request);

    /**
     * 插件启用自己的计划
     */
    void enableScoped(String id, String source);

    /**
     * 插件禁用自己的计划
     */
    void disableScoped(String id, String source);

    /**
     * 插件删除自己的计划
     */
    void deleteScoped(String id, String source);

    /**
     * 插件手动触发自己的计划
     *
     * @return runId
     */
    String triggerScoped(String id, String source);

    /**
     * 插件获取自己的计划详情
     */
    ScheduleVO getScoped(String id, String source);

    /**
     * 插件分页查询自己的计划（source 强制绑定）
     */
    PageResult<ScheduleVO> listScoped(ScheduleQuery query, String source);

    /**
     * 插件分页查询自己计划的触发记录
     */
    PageResult<ScheduleRunVO> listRunsScoped(ScheduleRunQuery query, String source);

    /**
     * 插件获取自己计划触发记录的日志
     */
    List<TaskLog> getRunLogsScoped(String runId, String source);

    // ==================== 插件生命周期联动（PluginSpringContextFactory 调用） ====================

    /**
     * 插件加载：按稳定键（pluginId:key）upsert 声明式默认计划（ADR-0011 D5）
     *
     * <p>upsert 冲突语义：用户改过的计划（user_modified=1）整体跳过；
     * 用户删除的计划（逻辑删除墓碑）不复活；未修改过的随声明演进更新。
     *
     * @param pluginId     插件ID
     * @param source       插件来源
     * @param declarations 声明列表（可为空）
     */
    void upsertDeclarations(String pluginId, String source, List<ScheduleDeclaration> declarations);

    /**
     * 插件停用/热重载：暂停其全部计划（保留 enabled 用户意图，记暂停原因）
     */
    void pauseByPlugin(String pluginId, String reason);

    /**
     * 插件加载：恢复其暂停的计划（清除系统暂停标记，enabled=1 的重新注册调度）
     */
    void resumeByPlugin(String pluginId);

    /**
     * 插件卸载移除：物理删除其全部计划 + 触发记录 + 日志（对齐 purgeTasks 语义）
     */
    void purgeByPlugin(String pluginId);
}
