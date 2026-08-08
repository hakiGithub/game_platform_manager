package com.gameplatform.controller.docker;

import com.gameplatform.annotation.OperationLog;
import com.gameplatform.common.result.Result;
import com.gameplatform.dto.docker.ContainerLogQueryDTO;
import com.gameplatform.dto.docker.ContainerOperationDTO;
import com.gameplatform.service.docker.DockerContainerService;
import com.gameplatform.vo.docker.ContainerDetailVO;
import com.gameplatform.vo.docker.ContainerHealthVO;
import com.gameplatform.vo.docker.ContainerListVO;
import com.gameplatform.vo.docker.ContainerStatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Docker容器管理控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "Docker容器管理", description = "Docker容器相关接口")
@RestController
@RequestMapping("/docker/hosts/{hostId}/containers")
@RequiredArgsConstructor
@Validated
public class DockerContainerController {

    private final DockerContainerService containerService;

    /**
     * 获取容器列表
     */
    @Operation(summary = "获取容器列表", description = "获取指定主机上的Docker容器列表")
    @GetMapping
    public Result<List<ContainerListVO>> list(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "状态筛选") @RequestParam(required = false) String status,
            @Parameter(description = "关键词搜索") @RequestParam(required = false) String keyword,
            @Parameter(description = "关联状态筛选") @RequestParam(required = false) Boolean linked) {
        
        List<ContainerListVO> containers = containerService.listContainers(hostId, status, keyword, linked);
        return Result.success(containers);
    }

    /**
     * 获取容器详情
     */
    @Operation(summary = "获取容器详情", description = "获取指定容器的详细信息")
    @GetMapping("/{containerId}")
    public Result<ContainerDetailVO> detail(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId) {
        
        ContainerDetailVO detail = containerService.getContainerDetail(hostId, containerId);
        return Result.success(detail);
    }

    /**
     * 启动容器
     */
    @Operation(summary = "启动容器", description = "启动已停止的容器")
    @PostMapping("/{containerId}/start")
    @OperationLog(type = "START", target = "CONTAINER", description = "启动容器")
    public Result<DockerContainerService.ContainerOperationResult> start(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId) {
        
        DockerContainerService.ContainerOperationResult result = containerService.startContainer(hostId, containerId);
        return Result.success(result);
    }

    /**
     * 停止容器
     */
    @Operation(summary = "停止容器", description = "停止运行中的容器")
    @PostMapping("/{containerId}/stop")
    @OperationLog(type = "STOP", target = "CONTAINER", description = "停止容器")
    public Result<DockerContainerService.ContainerOperationResult> stop(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @RequestBody(required = false) ContainerOperationDTO dto) {
        
        DockerContainerService.ContainerOperationResult result = containerService.stopContainer(hostId, containerId, dto);
        return Result.success(result);
    }

    /**
     * 重启容器
     */
    @Operation(summary = "重启容器", description = "重启容器")
    @PostMapping("/{containerId}/restart")
    @OperationLog(type = "RESTART", target = "CONTAINER", description = "重启容器")
    public Result<DockerContainerService.ContainerOperationResult> restart(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @RequestBody(required = false) ContainerOperationDTO dto) {
        
        DockerContainerService.ContainerOperationResult result = containerService.restartContainer(hostId, containerId, dto);
        return Result.success(result);
    }

    /**
     * 删除容器
     */
    @Operation(summary = "删除容器", description = "删除容器")
    @DeleteMapping("/{containerId}")
    @OperationLog(type = "DELETE", target = "CONTAINER", description = "删除容器")
    public Result<DockerContainerService.ContainerOperationResult> delete(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @RequestBody(required = false) ContainerOperationDTO dto) {
        
        DockerContainerService.ContainerOperationResult result = containerService.deleteContainer(hostId, containerId, dto);
        return Result.success(result);
    }

    /**
     * 获取容器资源统计
     */
    @Operation(summary = "获取容器资源统计", description = "获取容器实时资源使用统计")
    @GetMapping("/{containerId}/stats")
    public Result<ContainerStatsVO> stats(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId) {
        
        ContainerStatsVO stats = containerService.getContainerStats(hostId, containerId);
        return Result.success(stats);
    }

    /**
     * 获取容器健康状态
     */
    @Operation(summary = "获取容器健康状态", description = "获取容器健康检查状态")
    @GetMapping("/{containerId}/health")
    public Result<ContainerHealthVO> health(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId) {
        
        ContainerHealthVO health = containerService.getContainerHealth(hostId, containerId);
        return Result.success(health);
    }

    /**
     * 获取容器日志
     */
    @Operation(summary = "获取容器日志", description = "获取容器运行日志")
    @GetMapping("/{containerId}/logs")
    public Result<DockerContainerService.ContainerLogVO> logs(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            ContainerLogQueryDTO query) {
        
        DockerContainerService.ContainerLogVO logs = containerService.getContainerLogs(hostId, containerId, query);
        return Result.success(logs);
    }
}
