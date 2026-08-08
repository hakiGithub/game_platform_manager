package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.InstanceIdDTO;
import com.gameplatform.plugin.l4d2.dto.MapTrimBatchDTO;
import com.gameplatform.plugin.l4d2.service.MapService;
import com.gameplatform.plugin.l4d2.vo.MapListVO;
import com.gameplatform.plugin.l4d2.vo.MissionInfoVO;
import com.gameplatform.plugin.l4d2.vo.VpkTrimResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 地图管理控制器
 * 提供 L4D2 地图的上传、删除、列表、热重载、裁剪、mission 解析等功能
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 地图管理", description = "L4D2 服务器地图管理接口")
@RestController
@RequestMapping("/api/plugin/l4d2/maps")
@RequiredArgsConstructor
@Validated
public class MapController {

    private final MapService mapService;

    /**
     * 获取地图列表
     */
    @Operation(summary = "获取地图列表", description = "从 VPK 文件解析获取地图列表")
    @GetMapping("/list")
    public Result<List<MapListVO>> getMapList(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        return Result.success(mapService.listMaps(instanceId));
    }

    /**
     * 上传地图
     */
    @Operation(summary = "上传地图", description = "上传 VPK 格式的地图文件")
    @PostMapping("/upload")
    public Result<MapListVO> uploadMap(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "地图文件") @RequestParam("file") MultipartFile file) {
        return Result.success(mapService.uploadMap(instanceId, file));
    }

    /**
     * 删除地图
     */
    @Operation(summary = "删除地图", description = "删除指定的地图文件")
    @DeleteMapping("/{mapName}")
    public Result<Void> deleteMap(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "地图名称（VPK 文件名）") @PathVariable String mapName) {
        mapService.deleteMap(instanceId, mapName);
        return Result.success();
    }

    /**
     * 刷新地图列表缓存
     */
    @Operation(summary = "刷新地图列表缓存", description = "清除地图列表缓存并重新加载")
    @PostMapping("/refresh")
    public Result<Void> refreshMapList(@Valid @RequestBody InstanceIdDTO dto) {
        mapService.refreshCache(dto.getInstanceId());
        return Result.success();
    }

    /**
     * 地图热重载
     */
    @Operation(summary = "地图热重载", description = "通过 RCON 触发服务端重新加载 addon 与 mission")
    @PostMapping("/hot-reload")
    public Result<Void> hotReload(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        mapService.hotReload(instanceId);
        return Result.success();
    }

    /**
     * VPK 手动裁剪
     */
    @Operation(summary = "VPK 手动裁剪", description = "裁剪指定 VPK 文件中的冗余资源（带备份）")
    @PostMapping("/{mapName}/trim")
    public Result<VpkTrimResultVO> trim(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "地图名称（VPK 文件名）") @PathVariable String mapName) {
        return Result.success(mapService.trimMap(instanceId, mapName));
    }

    /**
     * 批量裁剪
     */
    @Operation(summary = "批量裁剪", description = "批量裁剪多个 VPK 文件")
    @PostMapping("/trim-batch")
    public Result<List<VpkTrimResultVO>> trimBatch(@Valid @RequestBody MapTrimBatchDTO dto) {
        return Result.success(mapService.trimBatch(dto.getInstanceId(), dto.getMapNames()));
    }

    /**
     * 解析 VPK mission 信息
     */
    @Operation(summary = "解析 VPK mission 信息", description = "解析 VPK 中的 mission 文件，返回战役与章节信息")
    @GetMapping("/{mapName}/mission")
    public Result<MissionInfoVO> getMission(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "地图名称（VPK 文件名）") @PathVariable String mapName) {
        return Result.success(mapService.getMission(instanceId, mapName));
    }
}
