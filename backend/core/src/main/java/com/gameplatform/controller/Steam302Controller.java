package com.gameplatform.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.service.Steam302Service;
import com.gameplatform.vo.Steam302StatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Steam302 主机加速控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "Steam302 加速", description = "主机级 Steamcommunity 302 部署与管理")
@Slf4j
@RestController
@RequestMapping("/hosts/{hostId}/steam302")
@RequiredArgsConstructor
@Validated
public class Steam302Controller {

    private final Steam302Service steam302Service;

    @Operation(summary = "查询部署状态")
    @GetMapping("/status")
    public Result<Steam302StatusVO> status(
            @Parameter(description = "主机ID") @PathVariable Long hostId) {
        return Result.success(steam302Service.status(hostId));
    }

    @Operation(summary = "安装（提交任务中心异步执行）", description = "拉取镜像、起容器、信任 CA，返回任务ID")
    @PostMapping("/install")
    public Result<String> install(
            @Parameter(description = "主机ID") @PathVariable Long hostId) {
        return Result.success(steam302Service.install(hostId));
    }

    @Operation(summary = "启动")
    @PostMapping("/start")
    public Result<Void> start(
            @Parameter(description = "主机ID") @PathVariable Long hostId) {
        steam302Service.start(hostId);
        return Result.success();
    }

    @Operation(summary = "停止")
    @PostMapping("/stop")
    public Result<Void> stop(
            @Parameter(description = "主机ID") @PathVariable Long hostId) {
        steam302Service.stop(hostId);
        return Result.success();
    }

    @Operation(summary = "切换容器共享加速", description = "开启后 hosts 指向宿主机 LAN IP（bridge 容器可共享代理），立即重写生效")
    @PutMapping("/container-share")
    public Result<Void> setContainerShare(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body == null ? null : body.get("enabled");
        if (enabled == null) {
            return Result.fail("enabled 不能为空");
        }
        steam302Service.setContainerShare(hostId, enabled);
        return Result.success();
    }

    @Operation(summary = "读取服务开关配置", description = "S302.ini [Setting] 全部键值，保持文件顺序")
    @GetMapping("/config")
    public Result<Map<String, String>> getConfig(
            @Parameter(description = "主机ID") @PathVariable Long hostId) {
        return Result.success(steam302Service.getConfig(hostId));
    }

    @Operation(summary = "保存服务开关配置", description = "仅覆盖已存在的键，保存后需重启生效")
    @PutMapping("/config")
    public Result<Void> saveConfig(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @RequestBody Map<String, String> values) {
        steam302Service.saveConfig(hostId, values);
        return Result.success();
    }
}
