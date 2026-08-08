package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.InstanceIdDTO;
import com.gameplatform.plugin.l4d2.dto.ServerConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.ServerConfigService;
import com.gameplatform.plugin.l4d2.vo.ServerConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 服务器配置控制器
 * 提供 L4D2 服务器配置的管理功能，包括读取、更新、重载与文件直接编辑。
 *
 * <p>业务逻辑由 {@link ServerConfigService} 承载，Controller 仅负责参数转发与异常处理。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 服务器配置", description = "L4D2 服务器配置管理接口")
@RestController
@RequestMapping("/api/plugin/l4d2/server-config")
@RequiredArgsConstructor
@Validated
public class ServerConfigController {

    private final ServerConfigService serverConfigService;

    /**
     * 获取服务器配置
     */
    @Operation(summary = "获取服务器配置", description = "读取 server.cfg 并解析为 VO")
    @GetMapping("/get")
    public Result<ServerConfigVO> getConfig(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("获取服务器配置, instanceId: {}", instanceId);
        try {
            ServerConfigVO vo = serverConfigService.getServerConfig(instanceId);
            return Result.success(vo);
        } catch (L4D2PluginException e) {
            log.warn("获取服务器配置失败 instanceId={}, code={}, msg={}",
                    instanceId, e.getCode(), e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 更新服务器配置
     */
    @Operation(summary = "更新服务器配置", description = "写入 server.cfg 并同步多 tick 文件")
    @PostMapping("/update")
    public Result<Void> updateConfig(@Valid @RequestBody ServerConfigUpdateDTO dto) {
        log.info("更新服务器配置, instanceId: {}", dto.getInstanceId());
        try {
            serverConfigService.updateServerConfig(dto.getInstanceId(), dto);
            return Result.success();
        } catch (L4D2PluginException e) {
            log.warn("更新服务器配置失败 instanceId={}, code={}, msg={}",
                    dto.getInstanceId(), e.getCode(), e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 重载服务器配置
     */
    @Operation(summary = "重载服务器配置", description = "通过 RCON 执行 exec server.cfg")
    @PostMapping("/reload")
    public Result<Void> reloadConfig(@Valid @RequestBody InstanceIdDTO dto) {
        log.info("重载服务器配置, instanceId: {}", dto.getInstanceId());
        try {
            serverConfigService.reloadConfig(dto.getInstanceId());
            return Result.success("配置重载成功", null);
        } catch (L4D2PluginException e) {
            log.warn("重载服务器配置失败 instanceId={}, code={}, msg={}",
                    dto.getInstanceId(), e.getCode(), e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取服务器配置文件内容
     */
    @Operation(summary = "获取配置文件内容", description = "获取服务器配置文件的原始内容")
    @GetMapping("/file-content")
    public Result<String> getConfigFileContent(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "配置文件名") @RequestParam(defaultValue = "server.cfg") String fileName) {
        log.info("获取配置文件内容, instanceId: {}, fileName: {}", instanceId, fileName);
        try {
            String content = serverConfigService.getFileContent(instanceId, fileName);
            return Result.success(content);
        } catch (IllegalArgumentException e) {
            log.warn("非法文件名 instanceId={}, fileName={}", instanceId, fileName);
            return Result.fail(e.getMessage());
        } catch (L4D2PluginException e) {
            log.warn("获取配置文件内容失败 instanceId={}, code={}, msg={}",
                    instanceId, e.getCode(), e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 更新服务器配置文件内容
     */
    @Operation(summary = "更新配置文件内容", description = "直接更新服务器配置文件的原始内容")
    @PostMapping("/file-content")
    public Result<Void> updateConfigFileContent(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "配置文件名") @RequestParam(defaultValue = "server.cfg") String fileName,
            @Parameter(description = "配置内容") @RequestBody String content) {
        log.info("更新配置文件内容, instanceId: {}, fileName: {}", instanceId, fileName);
        try {
            serverConfigService.updateFileContent(instanceId, fileName, content);
            return Result.success();
        } catch (IllegalArgumentException e) {
            log.warn("非法文件名 instanceId={}, fileName={}", instanceId, fileName);
            return Result.fail(e.getMessage());
        } catch (L4D2PluginException e) {
            log.warn("更新配置文件内容失败 instanceId={}, code={}, msg={}",
                    instanceId, e.getCode(), e.getMessage());
            return Result.fail(e.getMessage());
        }
    }
}
