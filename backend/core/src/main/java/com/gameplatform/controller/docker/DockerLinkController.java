package com.gameplatform.controller.docker;

import com.gameplatform.common.result.Result;
import com.gameplatform.dto.docker.ContainerLinkDTO;
import com.gameplatform.service.docker.DockerContainerLinkService;
import com.gameplatform.vo.docker.ContainerLinkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Docker容器关联管理控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "Docker关联管理", description = "Docker容器关联相关接口")
@RestController
@RequestMapping("/docker/links")
@RequiredArgsConstructor
@Validated
public class DockerLinkController {

    private final DockerContainerLinkService linkService;

    /**
     * 创建关联
     */
    @Operation(summary = "创建关联", description = "手动创建容器与实例的关联")
    @PostMapping
    public Result<ContainerLinkVO> createLink(
            @Valid @RequestBody ContainerLinkDTO dto,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        Long userId = getUserId(userDetails);
        ContainerLinkVO link = linkService.createLink(dto, userId);
        return Result.success("关联创建成功", link);
    }

    /**
     * 更新关联
     */
    @Operation(summary = "更新关联", description = "更新容器关联信息")
    @PutMapping("/{id}")
    public Result<ContainerLinkVO> updateLink(
            @Parameter(description = "关联记录ID") @PathVariable Long id,
            @RequestBody ContainerLinkDTO dto) {
        
        ContainerLinkVO link = linkService.updateLink(id, dto);
        return Result.success("关联更新成功", link);
    }

    /**
     * 删除关联
     */
    @Operation(summary = "删除关联", description = "删除容器关联")
    @DeleteMapping("/{id}")
    public Result<Void> deleteLink(
            @Parameter(description = "关联记录ID") @PathVariable Long id) {
        
        linkService.deleteLink(id);
        return Result.success("关联删除成功", null);
    }

    /**
     * 获取关联详情
     */
    @Operation(summary = "获取关联详情", description = "根据ID获取关联详情")
    @GetMapping("/{id}")
    public Result<ContainerLinkVO> getLink(
            @Parameter(description = "关联记录ID") @PathVariable Long id) {
        
        ContainerLinkVO link = linkService.getLinkById(id);
        return Result.success(link);
    }

    /**
     * 获取关联列表
     */
    @Operation(summary = "获取关联列表", description = "获取容器关联列表")
    @GetMapping
    public Result<List<ContainerLinkVO>> listLinks(
            @Parameter(description = "主机ID") @RequestParam(required = false) Long hostId,
            @Parameter(description = "实例ID") @RequestParam(required = false) Long instanceId,
            @Parameter(description = "容器ID") @RequestParam(required = false) String containerId,
            @Parameter(description = "关联类型") @RequestParam(required = false) String linkType) {
        
        List<ContainerLinkVO> links = linkService.listLinks(hostId, instanceId, containerId, linkType);
        return Result.success(links);
    }

    /**
     * 执行自动关联
     */
    @Operation(summary = "执行自动关联", description = "根据镜像名称自动匹配并创建容器关联")
    @PostMapping("/auto")
    public Result<DockerContainerLinkService.AutoLinkResult> autoLink(
            @RequestBody AutoLinkRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        Long userId = getUserId(userDetails);
        DockerContainerLinkService.AutoLinkResult result = linkService.autoLink(request.hostId(), userId);
        return Result.success("自动关联完成", result);
    }

    /**
     * 从UserDetails获取用户ID
     */
    private Long getUserId(UserDetails userDetails) {
        // TODO: 从UserDetails中获取用户ID
        // 这里需要根据实际的UserDetails实现来获取
        return 1L; // 临时返回
    }

    /**
     * 自动关联请求
     */
    record AutoLinkRequest(Long hostId) {}
}
