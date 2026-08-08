package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 实例状态与生命周期控制器。
 * <p>
 * 提供实例运行状态查询与启动/停止/重启接口，供插件前端 Dashboard 使用。
 * 通过 {@link InstanceQueryService} SPI 调用宿主能力，避免前端直连 /server/start 等不存在的端点。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 实例管理", description = "实例状态查询与生命周期控制接口")
@RestController
@RequestMapping("/api/plugin/l4d2/instance")
@RequiredArgsConstructor
public class InstanceController {

    private final InstanceQueryService instanceQueryService;

    /**
     * 查询实例运行状态。
     * 返回核心实例表的 runStatus（已由 InstanceSyncService 双向同步维护），
     * 同时附带实例名/主机IP/部署类型等基础信息，供 Dashboard 展示服务器运行状态。
     *
     * @param instanceId 实例ID
     */
    @Operation(summary = "查询实例运行状态", description = "返回实例的 runStatus 和基础信息")
    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus(
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("查询实例运行状态, instanceId: {}", instanceId);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在: " + instanceId);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("instanceId", instance.getId());
        data.put("instanceName", instance.getInstanceName());
        data.put("hostId", instance.getHostId());
        data.put("hostIp", instance.getHostIp());
        data.put("gameCode", instance.getGameCode());
        data.put("deployType", instance.getDeployType());
        data.put("installPath", instance.getInstallPath());
        data.put("runStatus", instance.getRunStatus());
        data.put("runStatusDesc", instance.getRunStatusDesc());
        return Result.success(data);
    }

    /**
     * 启动实例。
     */
    @Operation(summary = "启动实例", description = "通过宿主能力启动指定实例")
    @PostMapping("/start")
    public Result<Void> start(
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("启动实例, instanceId: {}", instanceId);
        boolean ok = instanceQueryService.startInstance(instanceId);
        return ok ? Result.success("实例已启动", null) : Result.fail("启动失败");
    }

    /**
     * 停止实例。
     */
    @Operation(summary = "停止实例", description = "通过宿主能力停止指定实例")
    @PostMapping("/stop")
    public Result<Void> stop(
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("停止实例, instanceId: {}", instanceId);
        boolean ok = instanceQueryService.stopInstance(instanceId);
        return ok ? Result.success("实例已停止", null) : Result.fail("停止失败");
    }

    /**
     * 重启实例。
     */
    @Operation(summary = "重启实例", description = "通过宿主能力重启指定实例")
    @PostMapping("/restart")
    public Result<Void> restart(
            @Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("重启实例, instanceId: {}", instanceId);
        boolean ok = instanceQueryService.restartInstance(instanceId);
        return ok ? Result.success("实例已重启", null) : Result.fail("重启失败");
    }
}
