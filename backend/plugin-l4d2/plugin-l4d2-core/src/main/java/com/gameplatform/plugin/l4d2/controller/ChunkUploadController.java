package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.ChunkUploadInitDTO;
import com.gameplatform.plugin.l4d2.service.ChunkUploadService;
import com.gameplatform.plugin.l4d2.vo.ChunkUploadInitVO;
import com.gameplatform.plugin.l4d2.vo.ChunkUploadStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * L4D2 分片上传控制器：大文件分片上传，支持断点续传。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 分片上传", description = "大文件分片上传")
@RestController
@RequestMapping("/api/plugin/l4d2/chunk-upload")
@RequiredArgsConstructor
@Validated
public class ChunkUploadController {

    private final ChunkUploadService chunkUploadService;

    /**
     * 初始化分片上传。
     */
    @Operation(summary = "初始化分片上传", description = "校验大小、创建临时目录与上传记录")
    @PostMapping("/init")
    public Result<ChunkUploadInitVO> init(@Valid @RequestBody ChunkUploadInitDTO dto) {
        return Result.success(chunkUploadService.init(dto));
    }

    /**
     * 上传分片。
     */
    @Operation(summary = "上传分片", description = "写入指定分片并更新进度")
    @PostMapping("/{uploadId}/chunk")
    public Result<Void> uploadChunk(@Parameter(description = "上传ID") @PathVariable String uploadId,
                                    @Parameter(description = "分片索引") @RequestParam int index,
                                    @Parameter(description = "分片数据") @RequestParam("chunk") MultipartFile chunk) {
        chunkUploadService.uploadChunk(uploadId, index, chunk);
        return Result.success(null);
    }

    /**
     * 查询上传状态。
     */
    @Operation(summary = "查询上传状态", description = "返回已接收分片数与进度百分比")
    @GetMapping("/{uploadId}/status")
    public Result<ChunkUploadStatusVO> status(@Parameter(description = "上传ID") @PathVariable String uploadId) {
        return Result.success(chunkUploadService.status(uploadId));
    }

    /**
     * 完成上传。
     */
    @Operation(summary = "完成上传", description = "合并分片并上传到远程主机")
    @PostMapping("/{uploadId}/complete")
    public Result<Void> complete(@Parameter(description = "上传ID") @PathVariable String uploadId) {
        chunkUploadService.complete(uploadId);
        return Result.success(null);
    }

    /**
     * 取消上传。
     */
    @Operation(summary = "取消上传", description = "清理临时文件并删除上传记录")
    @PostMapping("/{uploadId}/cancel")
    public Result<Void> cancel(@Parameter(description = "上传ID") @PathVariable String uploadId) {
        chunkUploadService.cancel(uploadId);
        return Result.success(null);
    }
}
