package com.gameplatform.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.rcon.RconExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实例 RCON 控制器（ADR-0016，主应用薄 REST）。
 * <p>
 * 提供插件无关的 RCON 连通性验证与命令执行入口，调用方审计标识固定为 main-app。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "实例 RCON", description = "实例 RCON 命令执行与连通性验证")
@RestController
@RequestMapping("/instances/{instanceId}/rcon")
@RequiredArgsConstructor
@Validated
public class InstanceRconController {

    private final RconExecutor rconExecutor;

    /**
     * 执行 RCON 命令
     */
    @Operation(summary = "执行 RCON 命令", description = "在指定实例上执行 Source RCON 命令")
    @PostMapping("/execute")
    public Result<String> execute(@PathVariable Long instanceId, @Valid @RequestBody RconCommandDTO dto) {
        String output = rconExecutor.execute(instanceId, dto.getCommand(), null, RconExecutor.MAIN_APP_CALLER);
        return Result.success(output);
    }

    /**
     * 测试 RCON 连通性
     */
    @Operation(summary = "测试 RCON 连通性", description = "建连 + 认证验证，不执行业务命令")
    @GetMapping("/ping")
    public Result<Boolean> ping(@PathVariable Long instanceId) {
        return Result.success(rconExecutor.testConnection(instanceId, RconExecutor.MAIN_APP_CALLER));
    }

    /**
     * RCON 命令请求体
     */
    @Data
    public static class RconCommandDTO {
        @NotBlank(message = "命令不能为空")
        private String command;
    }
}
