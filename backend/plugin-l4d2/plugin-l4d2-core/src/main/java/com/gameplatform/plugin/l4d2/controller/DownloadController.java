package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.ParseLinkDTO;
import com.gameplatform.plugin.l4d2.dto.ParseWorkshopDTO;
import com.gameplatform.plugin.l4d2.dto.UrlDownloadDTO;
import com.gameplatform.plugin.l4d2.dto.WorkshopDownloadDTO;
import com.gameplatform.plugin.l4d2.service.DownloadService;
import com.gameplatform.plugin.l4d2.service.WorkshopDownloadService;
import com.gameplatform.plugin.l4d2.vo.DownloadTaskVO;
import com.gameplatform.plugin.l4d2.vo.LinkParseResultVO;
import com.gameplatform.plugin.l4d2.vo.WorkshopParseResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * L4D2 下载管理控制器：URL 下载、Workshop 下载、任务查询、取消与删除。
 *
 * <p>路径前缀：{@code /api/plugin/l4d2/download}
 *
 * <p>Task 4.1 实现 URL 下载端点；Task 4.2 注入 {@link WorkshopDownloadService}
 * 完成 {@code /workshop}、{@code /parse-link}、{@code /parse-workshop} 端点。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Tag(name = "L4D2 下载管理", description = "URL/Workshop 资源下载与任务管理")
@RestController
@RequestMapping("/api/plugin/l4d2/download")
@RequiredArgsConstructor
@Validated
public class DownloadController {

    private final DownloadService downloadService;
    private final WorkshopDownloadService workshopDownloadService;

    /**
     * 创建 URL 下载任务（支持多 URL 切分）。
     */
    @Operation(summary = "创建 URL 下载任务", description = "支持在 url 字段中粘贴多个 URL（空白/换行分隔），每个 URL 创建独立任务")
    @PostMapping("/url")
    public Result<List<String>> createUrlTask(@Valid @RequestBody UrlDownloadDTO dto) {
        log.info("创建 URL 下载任务: instanceId={}", dto.getInstanceId());
        List<String> taskIds = downloadService.createUrlTasks(dto);
        return Result.success("下载任务已创建", taskIds);
    }

    /**
     * 创建 Workshop 下载任务（由 WorkshopDownloadService 解析后委托 DownloadService 创建任务）。
     */
    @Operation(summary = "创建 Workshop 下载任务", description = "由 WorkshopDownloadService 解析后调用 DownloadService 创建下载任务")
    @PostMapping("/workshop")
    public Result<List<String>> createWorkshopTask(@Valid @RequestBody WorkshopDownloadDTO dto) {
        log.info("创建 Workshop 下载任务: instanceId={}, urlOrId={}",
                dto.getInstanceId(), dto.getWorkshopUrlOrId());
        List<String> taskIds = workshopDownloadService.createWorkshopTasks(dto);
        return Result.success("Workshop 下载任务已创建", taskIds);
    }

    /**
     * 解析下载链接（通用预览，先尝试 Workshop，不支持则返回 unknown）。
     */
    @Operation(summary = "解析下载链接", description = "解析任意下载链接为可下载项列表（预览）")
    @PostMapping("/parse-link")
    public Result<LinkParseResultVO> parseLink(@Valid @RequestBody ParseLinkDTO dto) {
        return Result.success(workshopDownloadService.parseLink(dto.getUrl()));
    }

    /**
     * 解析 Workshop 链接为可下载项列表（预览）。
     */
    @Operation(summary = "解析 Workshop 链接", description = "解析 Workshop URL/ID 为可下载项列表（预览）")
    @PostMapping("/parse-workshop")
    public Result<WorkshopParseResultVO> parseWorkshop(@Valid @RequestBody ParseWorkshopDTO dto) {
        return Result.success(workshopDownloadService.parseWorkshop(dto.getUrl()));
    }

    /**
     * 任务列表（按 startTime 倒序）。
     */
    @Operation(summary = "任务列表", description = "查询指定实例的下载任务，支持状态过滤")
    @GetMapping("/tasks")
    public Result<List<DownloadTaskVO>> listTasks(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "状态过滤（可选）") @RequestParam(required = false) String status) {
        return Result.success(downloadService.listTasks(instanceId, status));
    }

    /**
     * 任务详情。
     */
    @Operation(summary = "任务详情", description = "获取指定下载任务的详细信息")
    @GetMapping("/tasks/{taskId}")
    public Result<DownloadTaskVO> getTask(
            @Parameter(description = "任务ID") @PathVariable String taskId) {
        return Result.success(downloadService.getTask(taskId));
    }

    /**
     * 取消任务。
     */
    @Operation(summary = "取消任务", description = "取消指定下载任务（设置内存取消标志 + 更新 DB 状态）")
    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Void> cancelTask(
            @Parameter(description = "任务ID") @PathVariable String taskId) {
        log.info("取消下载任务: taskId={}", taskId);
        downloadService.cancel(taskId);
        return Result.success();
    }

    /**
     * 删除任务（仅终态）。
     */
    @Operation(summary = "删除任务", description = "删除指定下载任务记录（仅终态可删除）")
    @DeleteMapping("/tasks/{taskId}")
    public Result<Void> deleteTask(
            @Parameter(description = "任务ID") @PathVariable String taskId) {
        log.info("删除下载任务: taskId={}", taskId);
        downloadService.delete(taskId);
        return Result.success();
    }
}
