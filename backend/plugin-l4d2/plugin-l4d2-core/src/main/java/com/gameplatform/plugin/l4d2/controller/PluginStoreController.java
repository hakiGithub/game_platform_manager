package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.PluginStoreDownloadDTO;
import com.gameplatform.plugin.l4d2.service.PluginStoreService;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDetailVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDownloadTaskVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * L4D2 插件商店控制器：浏览 GitHub 插件仓库并下载到实例。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 插件商店", description = "GitHub 插件商店浏览与下载")
@RestController
@RequestMapping("/api/plugin/l4d2/plugin-store")
@RequiredArgsConstructor
@Validated
public class PluginStoreController {

    private final PluginStoreService pluginStoreService;

    /**
     * 商店列表（含分页与关键词过滤）。
     */
    @Operation(summary = "商店列表", description = "查询 GitHub 插件商店列表，支持关键词与分类过滤")
    @GetMapping("/list")
    public Result<List<PluginStoreItemVO>> list(
            @Parameter(description = "关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "分类") @RequestParam(required = false) String category,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "20") int size) {
        log.info("查询插件商店列表: keyword={}, category={}, page={}, size={}",
                keyword, category, page, size);
        List<PluginStoreItemVO> all = pluginStoreService.list(keyword, category);
        long total = all.size();
        int from = Math.max(0, (page - 1) * size);
        int to = (int) Math.min(total, from + size);
        List<PluginStoreItemVO> pageList = from >= total
                ? List.of()
                : all.subList(from, to);
        return Result.success(pageList);
    }

    /**
     * 商店详情。
     */
    @Operation(summary = "商店详情", description = "获取插件详情（含 README 与文件列表）")
    @GetMapping("/{pluginId}")
    public Result<PluginStoreDetailVO> detail(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        log.info("查询插件商店详情: pluginId={}", pluginId);
        return Result.success(pluginStoreService.detail(pluginId));
    }

    /**
     * README 内容（Markdown 原文）。
     */
    @Operation(summary = "README 内容", description = "获取插件 README Markdown 原文")
    @GetMapping("/{pluginId}/readme")
    public Result<String> readme(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        log.info("查询插件 README: pluginId={}", pluginId);
        return Result.success(pluginStoreService.readme(pluginId));
    }

    /**
     * 下载插件到指定实例（异步执行）。
     */
    @Operation(summary = "下载到实例", description = "异步下载插件并安装到指定实例")
    @PostMapping("/download")
    public Result<String> download(@Valid @RequestBody PluginStoreDownloadDTO dto) {
        log.info("下载插件到实例: instanceId={}, pluginId={}",
                dto.getInstanceId(), dto.getPluginId());
        String taskId = pluginStoreService.download(dto);
        return Result.success("下载任务已创建", taskId);
    }

    /**
     * 下载任务列表。
     */
    @Operation(summary = "下载任务列表", description = "查询指定实例的下载任务")
    @GetMapping("/tasks")
    public Result<List<PluginStoreDownloadTaskVO>> tasks(
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        return Result.success(pluginStoreService.listTasks(instanceId));
    }

    /**
     * 取消下载。
     */
    @Operation(summary = "取消下载", description = "取消指定下载任务")
    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Void> cancel(
            @Parameter(description = "任务ID") @PathVariable String taskId) {
        log.info("取消下载任务: taskId={}", taskId);
        pluginStoreService.cancel(taskId);
        return Result.success();
    }
}
