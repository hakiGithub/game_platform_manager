package com.gameplatform.controller.docker;

import com.gameplatform.common.result.Result;
import com.gameplatform.dto.docker.FileContentUpdateDTO;
import com.gameplatform.dto.docker.FileCopyDTO;
import com.gameplatform.service.docker.DockerFileService;
import com.gameplatform.vo.docker.ContainerFileInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Docker文件管理控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "Docker文件管理", description = "Docker容器文件管理相关接口")
@RestController
@RequestMapping("/docker/hosts/{hostId}/containers/{containerId}/files")
@RequiredArgsConstructor
@Validated
public class DockerFileController {

    private final DockerFileService fileService;

    /**
     * 获取文件列表
     */
    @Operation(summary = "获取文件列表", description = "浏览容器内指定目录的文件列表")
    @GetMapping
    public Result<DockerFileService.FileListResult> listFiles(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @Parameter(description = "目录路径") @RequestParam(required = false, defaultValue = "/") String path,
            @Parameter(description = "是否显示隐藏文件") @RequestParam(required = false, defaultValue = "false") Boolean showHidden) {
        
        DockerFileService.FileListResult result = fileService.listFiles(hostId, containerId, path, showHidden);
        return Result.success(result);
    }

    /**
     * 获取文件内容
     */
    @Operation(summary = "获取文件内容", description = "获取容器内文件内容")
    @GetMapping("/content")
    public Result<DockerFileService.FileContentResult> getFileContent(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @Parameter(description = "文件路径") @RequestParam String path,
            @Parameter(description = "文件编码") @RequestParam(required = false, defaultValue = "UTF-8") String encoding,
            @Parameter(description = "读取行数限制") @RequestParam(required = false) Integer lines) {
        
        DockerFileService.FileContentResult result = fileService.getFileContent(hostId, containerId, path, encoding, lines);
        return Result.success(result);
    }

    /**
     * 更新文件内容
     */
    @Operation(summary = "更新文件内容", description = "更新容器内文件内容")
    @PutMapping("/content")
    public Result<DockerFileService.FileUpdateResult> updateFileContent(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @RequestBody FileContentUpdateDTO dto) {
        
        DockerFileService.FileUpdateResult result = fileService.updateFileContent(hostId, containerId, dto);
        return Result.success(result);
    }

    /**
     * 删除文件
     */
    @Operation(summary = "删除文件", description = "删除容器内文件或空目录")
    @DeleteMapping
    public Result<DockerFileService.FileDeleteResult> deleteFile(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @Parameter(description = "文件路径") @RequestParam String path) {
        
        DockerFileService.FileDeleteResult result = fileService.deleteFile(hostId, containerId, path);
        return Result.success(result);
    }

    /**
     * 上传文件
     */
    @Operation(summary = "上传文件", description = "上传文件到容器指定目录")
    @PostMapping("/upload")
    public Result<DockerFileService.FileUploadResult> uploadFile(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @Parameter(description = "上传的文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "目标目录路径") @RequestParam(required = false, defaultValue = "/") String path,
            @Parameter(description = "是否覆盖") @RequestParam(required = false, defaultValue = "false") Boolean overwrite) {
        
        DockerFileService.FileUploadResult result = fileService.uploadFile(hostId, containerId, file, path, overwrite);
        return Result.success(result);
    }

    /**
     * 下载文件
     */
    @Operation(summary = "下载文件", description = "从容器下载文件")
    @GetMapping("/download")
    public void downloadFile(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @Parameter(description = "文件路径") @RequestParam String path,
            HttpServletResponse response) {
        
        fileService.downloadFile(hostId, containerId, path, response);
    }

    /**
     * 拷贝文件
     */
    @Operation(summary = "拷贝文件", description = "在容器与主机间拷贝文件")
    @PostMapping("/copy")
    public Result<DockerFileService.FileCopyResult> copyFile(
            @Parameter(description = "主机ID") @PathVariable Long hostId,
            @Parameter(description = "容器ID") @PathVariable String containerId,
            @RequestBody FileCopyDTO dto) {
        
        DockerFileService.FileCopyResult result = fileService.copyFile(hostId, containerId, dto);
        return Result.success(result);
    }
}
