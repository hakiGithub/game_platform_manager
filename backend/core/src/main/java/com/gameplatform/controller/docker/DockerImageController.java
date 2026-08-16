package com.gameplatform.controller.docker;

import com.gameplatform.annotation.OperationLog;
import com.gameplatform.common.result.Result;
import com.gameplatform.service.docker.DockerImageService;
import com.gameplatform.vo.docker.ImageListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Docker镜像管理控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "Docker镜像管理", description = "Docker镜像相关接口")
@RestController
@RequestMapping("/docker/hosts/{hostId}/images")
@RequiredArgsConstructor
@Validated
public class DockerImageController {

    private final DockerImageService imageService;

    /**
     * 获取镜像列表
     *
     * <p>返回 {images, total}：前端 store 取 data.images（此前返回数组导致列表空白）。</p>
     */
    @Operation(summary = "获取镜像列表", description = "获取主机上的Docker镜像列表")
    @GetMapping
    public Result<java.util.Map<String, Object>> list(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "关键词搜索") @RequestParam(required = false) String keyword,
            @Parameter(description = "是否只显示悬空镜像") @RequestParam(required = false) Boolean dangling) {

        List<ImageListVO> images = imageService.listImages(hostId, keyword, dangling);
        return Result.success(java.util.Map.of(
                "images", images,
                "total", images == null ? 0 : images.size()));
    }

    /**
     * 删除镜像
     */
    @Operation(summary = "删除镜像", description = "删除指定的镜像")
    @DeleteMapping("/{imageId}")
    @OperationLog(type = "DELETE", target = "IMAGE", description = "删除镜像")
    public Result<DockerImageService.ImageDeleteResult> delete(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "镜像ID") @PathVariable String imageId,
            @Parameter(description = "是否强制删除") @RequestParam(required = false, defaultValue = "false") Boolean force) {
        
        DockerImageService.ImageDeleteResult result = imageService.deleteImage(hostId, imageId, force);
        return Result.success(result);
    }

    /**
     * 清理悬空镜像
     */
    @Operation(summary = "清理悬空镜像", description = "清理无标签的悬空镜像")
    @PostMapping("/prune")
    @OperationLog(type = "PRUNE", target = "IMAGE", description = "清理悬空镜像")
    public Result<DockerImageService.ImagePruneResult> prune(
            @Parameter(description = "主机ID") @PathVariable Long hostId) {
        
        DockerImageService.ImagePruneResult result = imageService.pruneImages(hostId);
        return Result.success(result);
    }
}
