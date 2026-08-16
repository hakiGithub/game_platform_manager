package com.gameplatform.controller;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.task.TaskLog;
import com.gameplatform.plugin.task.TaskQuery;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.plugin.task.TaskVO;
import com.gameplatform.service.TaskAdminService;
import com.gameplatform.vo.TaskStatsVO;
import com.gameplatform.vo.TaskTypeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务中心控制器
 *
 * <p>提供任务提交、查询、取消、重试、删除、统计等 REST API。
 * 所有接口需认证（由 SecurityConfig 统一控制）。
 *
 * <p>接口清单（ADR-007 REST API）：
 * <ul>
 *   <li>POST   /tasks              提交任务</li>
 *   <li>GET    /tasks              分页查询任务列表</li>
 *   <li>GET    /tasks/{taskId}      获取任务详情</li>
 *   <li>GET    /tasks/{taskId}/logs 获取任务日志（增量查询）</li>
 *   <li>POST   /tasks/{taskId}/cancel 取消任务</li>
 *   <li>POST   /tasks/{taskId}/retry  重试任务</li>
 *   <li>DELETE /tasks/{taskId}      删除任务</li>
 *   <li>GET    /tasks/types         获取已注册的任务类型列表</li>
 *   <li>GET    /tasks/stats         任务统计</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "任务中心", description = "任务提交、查询、管理接口")
@Slf4j
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskAdminService taskAdminService;

    // ==================== 提交 ====================

    /**
     * 提交任务
     *
     * <p>通用提交入口，调用方在 body 中指定 taskType/source/scope/payload。
     * submitter 由后端从 SecurityContext 自动填充。
     */
    @Operation(summary = "提交任务", description = "提交异步任务，返回任务ID")
    @PostMapping
    public Result<String> submit(@RequestBody TaskSubmitRequest request) {
        // 自动填充提交者（前端传入的 submitter 会被覆盖，防止伪造）
        String submitter = getCurrentUsername();
        request.setSubmitter(submitter);
        String taskId = taskService.submit(request);
        return Result.success(taskId);
    }

    // ==================== 查询 ====================

    /**
     * 分页查询任务列表
     */
    @Operation(summary = "分页查询任务列表", description = "支持按来源/类型/状态/时间/关键字筛选")
    @GetMapping
    public Result<PageResult<TaskVO>> list(TaskListQuery query) {
        TaskQuery taskQuery = TaskQuery.builder()
                .source(query.getSource())
                .taskType(query.getTaskType())
                .status(query.getStatus())
                .scopeKey(query.getScopeKey())
                .submitter(query.getSubmitter())
                .startTime(query.getStartTime())
                .endTime(query.getEndTime())
                .keyword(query.getKeyword())
                .page(query.getPage() != null ? query.getPage() : 1)
                .size(query.getSize() != null ? query.getSize() : 20)
                .build();
        PageResult<TaskVO> result = taskAdminService.listTasks(taskQuery);
        return Result.success(result);
    }

    /**
     * 获取任务详情
     */
    @Operation(summary = "获取任务详情", description = "返回任务完整信息，含 Handler 元信息（retryable/maxRetryCount）")
    @GetMapping("/{taskId}")
    public Result<TaskVO> getById(
            @Parameter(description = "任务ID") @PathVariable String taskId) {
        TaskVO vo = taskAdminService.getTask(taskId);
        return Result.success(vo);
    }

    /**
     * 获取任务日志（增量查询，ADR-037）
     *
     * <p>首次拉取不传 afterId，返回最近 100 条；
     * 后续传入上次最后一条日志的 ID，返回该 ID 之后的新日志。
     */
    @Operation(summary = "获取任务日志", description = "增量查询：不传 afterId 返回最近 100 条，传 afterId 返回该 ID 之后的日志")
    @GetMapping("/{taskId}/logs")
    public Result<List<TaskLog>> getLogs(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @Parameter(description = "上次最后一条日志ID（首次拉取不传）") @RequestParam(required = false) String afterId) {
        List<TaskLog> logs = taskAdminService.getTaskLogsAfter(taskId, afterId);
        return Result.success(logs);
    }

    // ==================== 管理 ====================

    /**
     * 取消任务
     *
     * <p>PENDING 直接置 CANCELLED；RUNNING 协作式取消（Handler 自检 ctx.isCancelled 退出）。
     */
    @Operation(summary = "取消任务", description = "协作式取消，PENDING 直接取消，RUNNING 等待 Handler 优雅退出")
    @PostMapping("/{taskId}/cancel")
    public Result<Boolean> cancel(
            @Parameter(description = "任务ID") @PathVariable String taskId) {
        boolean accepted = taskAdminService.cancel(taskId);
        return Result.success(accepted);
    }

    /**
     * 重试任务（ADR-027 不修改原任务状态）
     *
     * <p>原任务必须为 FAILED 或 CANCELLED，且 Handler 声明可重试、未超过重试上限。
     * 创建新任务记录，parent_task_id 关联原任务。
     */
    @Operation(summary = "重试任务", description = "基于原任务 payload 创建新任务，原任务状态不变")
    @PostMapping("/{taskId}/retry")
    public Result<String> retry(
            @Parameter(description = "原任务ID") @PathVariable String taskId) {
        String newTaskId = taskAdminService.retry(taskId);
        return Result.success(newTaskId);
    }

    /**
     * 删除任务（软删除，仅终态可删除）
     */
    @Operation(summary = "删除任务", description = "软删除，仅终态任务（COMPLETED/FAILED/CANCELLED）可删除")
    @DeleteMapping("/{taskId}")
    public Result<Boolean> delete(
            @Parameter(description = "任务ID") @PathVariable String taskId) {
        boolean deleted = taskAdminService.delete(taskId);
        return Result.success(deleted);
    }

    // ==================== 元信息 ====================

    /**
     * 获取已注册的任务类型列表
     */
    @Operation(summary = "获取任务类型列表", description = "返回所有已注册的任务类型（来源 + 类型 + 显示名）")
    @GetMapping("/types")
    public Result<List<TaskTypeVO>> listTypes() {
        List<TaskTypeVO> types = taskAdminService.listTypes();
        return Result.success(types);
    }

    /**
     * 任务统计（多维聚合，ADR-015）
     */
    @Operation(summary = "任务统计", description = "按状态/来源/类型聚合统计，支持时间范围过滤")
    @GetMapping("/stats")
    public Result<TaskStatsVO> stats(
            @Parameter(description = "起始时间（含），格式 yyyy-MM-dd'T'HH:mm:ss") @RequestParam(required = false) LocalDateTime startTime,
            @Parameter(description = "结束时间（含），格式 yyyy-MM-dd'T'HH:mm:ss") @RequestParam(required = false) LocalDateTime endTime) {
        TaskStatsVO stats = taskAdminService.getStats(startTime, endTime);
        return Result.success(stats);
    }

    // ==================== 私有方法 ====================

    /**
     * 从 SecurityContext 获取当前登录用户名
     */
    private String getCurrentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                String name = auth.getName();
                return name != null ? name : "SYSTEM";
            }
        } catch (Exception e) {
            log.warn("获取当前用户名失败: {}", e.getMessage());
        }
        return "SYSTEM";
    }

    /**
     * 任务列表查询参数（GET 请求绑定用）
     *
     * <p>与 {@link TaskQuery} 字段一致，但使用普通 setter（适配 @ModelAttribute 绑定）。
     */
    @lombok.Data
    public static class TaskListQuery {
        private String source;
        private String taskType;
        private String status;
        private String scopeKey;
        private String submitter;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String keyword;
        private Integer page;
        private Integer size;
    }
}
