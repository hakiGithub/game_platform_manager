package com.gameplatform.controller;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.schedule.ScheduleCreateRequest;
import com.gameplatform.plugin.schedule.ScheduleQuery;
import com.gameplatform.plugin.schedule.ScheduleRunQuery;
import com.gameplatform.plugin.schedule.ScheduleRunVO;
import com.gameplatform.plugin.schedule.ScheduleUpdateRequest;
import com.gameplatform.plugin.schedule.ScheduleVO;
import com.gameplatform.plugin.task.TaskLog;
import com.gameplatform.schedule.ScheduleManagementService;
import com.gameplatform.vo.TaskTypeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 定时计划控制器（ADR-0011 D9）
 *
 * <p>接口清单：
 * <ul>
 *   <li>GET    /schedules                 分页查询计划（跨来源）</li>
 *   <li>POST   /schedules                 创建计划（source 由请求指定）</li>
 *   <li>GET    /schedules/handlers        已注册处理器列表（新建计划时选择）</li>
 *   <li>GET    /schedules/{id}            计划详情</li>
 *   <li>PUT    /schedules/{id}            更新计划（name/cron/payload）</li>
 *   <li>POST   /schedules/{id}/enable     启用计划</li>
 *   <li>POST   /schedules/{id}/disable    禁用计划（进行中的 run 跑完）</li>
 *   <li>DELETE /schedules/{id}            删除计划（取消进行中的 run）</li>
 *   <li>POST   /schedules/{id}/trigger    立即触发一次（返回 runId）</li>
 *   <li>GET    /schedules/{id}/runs       分页查询触发记录</li>
 *   <li>GET    /schedules/runs/{runId}    触发记录详情</li>
 *   <li>POST   /schedules/runs/{runId}/cancel  取消进行中的触发</li>
 *   <li>GET    /schedules/runs/{runId}/logs    触发记录执行日志</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "定时计划", description = "定时计划的查询、创建、启停、触发与运行历史")
@Slf4j
@RestController
@RequestMapping("/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleManagementService scheduleManagementService;

    // ==================== 查询 ====================

    @Operation(summary = "分页查询计划", description = "支持按来源/处理器/启用状态/关键字筛选，跨来源")
    @GetMapping
    public Result<PageResult<ScheduleVO>> list(ScheduleQuery query) {
        return Result.success(scheduleManagementService.list(query));
    }

    @Operation(summary = "获取计划详情", description = "含最近一次触发结果与下次触发时间")
    @GetMapping("/{id}")
    public Result<ScheduleVO> get(
            @Parameter(description = "计划ID") @PathVariable String id) {
        return Result.success(scheduleManagementService.get(id));
    }

    @Operation(summary = "获取处理器列表", description = "已注册的定时任务处理器（来源 + key + 显示名），新建计划时选择")
    @GetMapping("/handlers")
    public Result<List<TaskTypeVO>> listHandlers() {
        return Result.success(scheduleManagementService.listHandlers());
    }

    // ==================== 创建/更新/启停/删除 ====================

    @Operation(summary = "创建计划", description = "source 须为已注册处理器的来源（MAIN 或插件 gameCode）")
    @PostMapping
    public Result<String> create(@RequestBody ScheduleCreateBody body) {
        ScheduleCreateRequest request = ScheduleCreateRequest.builder()
                .name(body.getName())
                .handlerKey(body.getHandlerKey())
                .cron(body.getCron())
                .payload(body.getPayload())
                .enabled(body.getEnabled())
                .build();
        String id = scheduleManagementService.create(body.getSource(), request, getCurrentUsername());
        return Result.success(id);
    }

    @Operation(summary = "更新计划", description = "更新 name/cron/payload（置 user_modified，插件声明不再覆盖）")
    @PutMapping("/{id}")
    public Result<Boolean> update(
            @Parameter(description = "计划ID") @PathVariable String id,
            @RequestBody ScheduleUpdateRequest request) {
        scheduleManagementService.update(id, request, getCurrentUsername());
        return Result.success(true);
    }

    @Operation(summary = "启用计划", description = "注册 cron 调度（系统暂停状态由插件生命周期管理）")
    @PostMapping("/{id}/enable")
    public Result<Boolean> enable(
            @Parameter(description = "计划ID") @PathVariable String id) {
        scheduleManagementService.enable(id);
        return Result.success(true);
    }

    @Operation(summary = "禁用计划", description = "只停未来触发，进行中的 run 跑完")
    @PostMapping("/{id}/disable")
    public Result<Boolean> disable(
            @Parameter(description = "计划ID") @PathVariable String id) {
        scheduleManagementService.disable(id);
        return Result.success(true);
    }

    @Operation(summary = "删除计划", description = "取消进行中的 run，逻辑删除（声明式计划不复活），触发记录保留")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(
            @Parameter(description = "计划ID") @PathVariable String id) {
        scheduleManagementService.delete(id);
        return Result.success(true);
    }

    // ==================== 触发 ====================

    @Operation(summary = "立即触发一次", description = "产生 MANUAL 来源 run，遇上一轮仍在执行则记 SKIPPED")
    @PostMapping("/{id}/trigger")
    public Result<String> trigger(
            @Parameter(description = "计划ID") @PathVariable String id) {
        return Result.success(scheduleManagementService.trigger(id));
    }

    // ==================== 触发记录 ====================

    @Operation(summary = "分页查询触发记录", description = "按计划过滤，支持状态筛选")
    @GetMapping("/{id}/runs")
    public Result<PageResult<ScheduleRunVO>> listRuns(
            @Parameter(description = "计划ID") @PathVariable String id,
            ScheduleRunQuery query) {
        query.setScheduleId(id);
        return Result.success(scheduleManagementService.listRuns(query));
    }

    @Operation(summary = "获取触发记录详情", description = "含状态、payload 快照、起止时间与错误信息")
    @GetMapping("/runs/{runId}")
    public Result<ScheduleRunVO> getRun(
            @Parameter(description = "触发记录ID") @PathVariable String runId) {
        return Result.success(scheduleManagementService.getRun(runId));
    }

    @Operation(summary = "取消进行中的触发", description = "协作式取消，等待 Handler 优雅退出")
    @PostMapping("/runs/{runId}/cancel")
    public Result<Boolean> cancelRun(
            @Parameter(description = "触发记录ID") @PathVariable String runId) {
        scheduleManagementService.cancelRun(runId);
        return Result.success(true);
    }

    @Operation(summary = "获取触发日志", description = "按时间正序，最多 500 条")
    @GetMapping("/runs/{runId}/logs")
    public Result<List<TaskLog>> getRunLogs(
            @Parameter(description = "触发记录ID") @PathVariable String runId) {
        return Result.success(scheduleManagementService.getRunLogs(runId));
    }

    // ==================== 私有方法 ====================

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
     * 创建计划请求体（管理侧 REST 专用，比插件侧多一个 source 字段）
     */
    @lombok.Data
    public static class ScheduleCreateBody {
        /** 计划来源：MAIN 或插件 gameCode 大写 */
        private String source;
        private String name;
        private String handlerKey;
        private String cron;
        private Map<String, Object> payload;
        /** 是否启用（缺省 true） */
        private Boolean enabled;
    }
}
