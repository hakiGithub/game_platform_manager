package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.service.PresetService;
import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * L4D2 预设系统控制器：预设列表、详情、应用。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 预设系统", description = "L4D2 预设场景管理")
@RestController
@RequestMapping("/api/plugin/l4d2/presets")
@RequiredArgsConstructor
public class PresetController {

    private final PresetService presetService;

    @Operation(summary = "获取预设列表")
    @GetMapping("/list")
    public Result<List<PresetDetailVO>> list() {
        return Result.success(presetService.list());
    }

    @Operation(summary = "获取预设详情")
    @GetMapping("/{presetId}")
    public Result<PresetDetailVO> detail(@PathVariable String presetId) {
        PresetDetailVO vo = presetService.detail(presetId);
        if (vo == null) {
            return Result.fail("预设不存在: " + presetId);
        }
        return Result.success(vo);
    }

    @Operation(summary = "应用预设到实例")
    @PostMapping("/{presetId}/apply")
    public Result<Void> apply(
            @Parameter(description = "预设ID") @PathVariable String presetId,
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("Apply preset {} to instance {}", presetId, instanceId);
        presetService.apply(instanceId, presetId);
        return Result.success("预设应用成功", null);
    }
}
