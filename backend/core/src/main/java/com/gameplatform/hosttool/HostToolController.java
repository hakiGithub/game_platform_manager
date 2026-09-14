package com.gameplatform.hosttool;

import com.gameplatform.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主机环境工具控制器（ADR-0021 决策 5）
 *
 * <p>主机详情页"环境工具"面板的后端接口：状态查询 + 白名单工具一键安装。
 * 归主应用，不暴露给插件 SDK。</p>
 */
@Tag(name = "主机环境工具", description = "主机环境工具查询与安装（ADR-0021）")
@RestController
@RequestMapping("/hosts/{hostId}/tools")
@RequiredArgsConstructor
public class HostToolController {

    private final HostToolService hostToolService;

    @Operation(summary = "查询主机环境工具状态", description = "白名单工具的已装/未装状态 + 包管理器/提权/Docker 能力")
    @GetMapping
    public Result<HostToolService.HostToolsVO> getTools(
            @Parameter(description = "主机ID") @PathVariable Long hostId) {
        return Result.success(hostToolService.getTools(hostId));
    }

    @Operation(summary = "安装主机环境工具", description = "同步安装白名单工具（发行版自适应，超时约 2 分钟）")
    @PostMapping("/{tool}/install")
    public Result<String> install(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "工具名（白名单内）") @PathVariable String tool) {
        return Result.success("安装成功", hostToolService.install(hostId, tool));
    }
}
