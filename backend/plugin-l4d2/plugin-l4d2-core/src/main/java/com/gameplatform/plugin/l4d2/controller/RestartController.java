package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.RestartConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.dto.RestartDTO;
import com.gameplatform.plugin.l4d2.enums.RestartMode;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.RestartService;
import com.gameplatform.plugin.l4d2.vo.RestartConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * L4D2 服务器重启控制器。
 *
 * <p>对齐源项目 {@code controller/restart.go}：支持 RCON 模式（{@code _restart}）
 * 与命令模式（{@code docker restart} 或自定义命令）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 重启管理", description = "L4D2 服务器重启接口")
@RestController
@RequestMapping("/api/plugin/l4d2/restart")
@RequiredArgsConstructor
@Validated
public class RestartController {

    private final RestartService restartService;

    /**
     * 重启服务器（默认 AUTO 模式）。
     */
    @Operation(summary = "重启服务器", description = "按指定模式重启 L4D2 服务器；mode 省略时按配置决定")
    @PostMapping
    public Result<Void> restart(@Valid @RequestBody RestartDTO dto) {
        log.info("重启服务器, instanceId: {}, mode: {}", dto.getInstanceId(), dto.getMode());
        try {
            RestartMode mode = parseMode(dto.getMode());
            restartService.restart(dto.getInstanceId(), mode);
            return Result.success("重启命令已发送", null);
        } catch (L4D2PluginException e) {
            log.warn("重启失败 [{}]: {}", e.getCode(), e.getMessage());
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("重启被拒绝: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 强制 RCON 模式重启。
     */
    @Operation(summary = "强制 RCON 模式重启", description = "通过 RCON 协议发送 _restart 命令")
    @PostMapping("/rcon")
    public Result<Void> restartByRcon(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("RCON 模式重启, instanceId: {}", instanceId);
        try {
            restartService.restartByRcon(instanceId);
            return Result.success("RCON 重启命令已发送", null);
        } catch (L4D2PluginException e) {
            log.warn("RCON 重启失败 [{}]: {}", e.getCode(), e.getMessage());
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("RCON 重启被拒绝: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 强制命令模式重启。
     */
    @Operation(summary = "强制命令模式重启", description = "通过 shell 执行 docker restart 或自定义命令")
    @PostMapping("/command")
    public Result<Void> restartByCommand(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("命令模式重启, instanceId: {}", instanceId);
        try {
            restartService.restartByCommand(instanceId);
            return Result.success("命令重启已执行", null);
        } catch (L4D2PluginException e) {
            log.warn("命令重启失败 [{}]: {}", e.getCode(), e.getMessage());
            return Result.fail(e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("命令重启被拒绝: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取重启配置。
     */
    @Operation(summary = "获取重启配置", description = "返回当前重启配置与可用模式")
    @GetMapping("/config")
    public Result<RestartConfigVO> getConfig() {
        return Result.success(restartService.getConfig());
    }

    /**
     * 更新重启配置。
     */
    @Operation(summary = "更新重启配置", description = "更新 byRcon / containerName / customCmd")
    @PostMapping("/config")
    public Result<Void> updateConfig(@Valid @RequestBody RestartConfigUpdateDTO dto) {
        log.info("更新重启配置: byRcon={}, containerName={}, customCmd={}",
                dto.getByRcon(), dto.getContainerName(), dto.getCustomCmd());
        restartService.setConfig(dto);
        return Result.success();
    }

    // ===== 私有方法 =====

    /**
     * 解析 mode 字符串为枚举；null/空字符串默认 AUTO。
     */
    private RestartMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return RestartMode.AUTO;
        }
        try {
            return RestartMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "无效的 mode 参数：" + mode + "，应为 AUTO/RCON/COMMAND");
        }
    }
}
