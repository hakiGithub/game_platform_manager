package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.CrawlRequestDTO;
import com.gameplatform.plugin.l4d2.service.MapCenterService;
import com.gameplatform.plugin.l4d2.vo.CrawlStatusVO;
import com.gameplatform.plugin.l4d2.vo.MapCenterVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * L4D2 地图中心控制器。
 *
 * <p>提供地图中心浏览（分页/详情）与爬虫管理（触发/状态查询）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 地图中心", description = "地图中心浏览与爬虫管理")
@RestController
@RequestMapping("/api/plugin/l4d2/map-center")
@RequiredArgsConstructor
@Validated
public class MapCenterController {

    private final MapCenterService mapCenterService;

    /**
     * 分页查询地图列表。
     */
    @Operation(summary = "分页查询地图列表", description = "按来源、关键字、模式过滤并分页返回地图")
    @GetMapping("/maps")
    public Result<PageResult<MapCenterVO>> listMaps(
            @Parameter(description = "数据来源，如 ORANGE") @RequestParam(required = false) String source,
            @Parameter(description = "关键字（中英文名称/作者/VPK文件名）") @RequestParam(required = false) String keyword,
            @Parameter(description = "游戏模式，如 合作 / 对抗") @RequestParam(required = false) String mode,
            @Parameter(description = "页码，默认 1") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量，默认 20") @RequestParam(defaultValue = "20") Integer size,
            @Parameter(description = "排序：newest/rating/size/views/name") @RequestParam(defaultValue = "newest") String sort) {
        return Result.success(mapCenterService.listMaps(source, keyword, mode, page, size, sort));
    }

    /**
     * 获取地图详情。
     */
    @Operation(summary = "获取地图详情", description = "按 sourceId 获取单个地图详情")
    @GetMapping("/maps/{sourceId}")
    public Result<MapCenterVO> getMap(
            @Parameter(description = "地图来源ID") @PathVariable String sourceId) {
        return Result.success(mapCenterService.getMap(sourceId));
    }

    /**
     * 触发地图爬取。
     */
    @Operation(summary = "触发地图爬取", description = "异步触发 FULL / INCREMENTAL 爬取，返回任务ID")
    @PostMapping("/crawl")
    public Result<String> triggerCrawl(@Valid @RequestBody CrawlRequestDTO dto) {
        String taskId = mapCenterService.triggerCrawl(dto.getType());
        return Result.success(taskId);
    }

    /**
     * 查询爬取任务状态。
     */
    @Operation(summary = "查询爬取状态", description = "返回当前/最近一次爬取任务进度")
    @GetMapping("/crawl/status")
    public Result<CrawlStatusVO> getCrawlStatus() {
        return Result.success(mapCenterService.getCrawlStatus());
    }
}
