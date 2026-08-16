package com.gameplatform.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.entity.BackupRecord;
import com.gameplatform.service.BackupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 备份管理控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "备份管理", description = "游戏存档/配置文件备份还原相关接口")
@RestController
@RequestMapping("/instances/{instanceId}/backups")
@RequiredArgsConstructor
@Validated
public class BackupController {

    private final BackupService backupService;

    /**
     * 获取备份列表
     */
    @Operation(summary = "获取备份列表", description = "获取指定实例的备份列表")
    @GetMapping
    public Result<List<BackupRecord>> list(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Parameter(description = "目标类型过滤") @RequestParam(required = false) String targetType) {
        List<BackupRecord> list;
        if (targetType != null && !targetType.isEmpty()) {
            list = backupService.getBackupListByTargetType(instanceId, targetType);
        } else {
            list = backupService.getBackupList(instanceId);
        }
        return Result.success(list);
    }

    /**
     * 创建数据库备份
     */
    @Operation(summary = "创建数据库备份", description = "创建游戏数据库备份")
    @PostMapping("/database")
    public Result<BackupRecord> createDatabaseBackup(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Valid @RequestBody DatabaseBackupRequest request) {
        BackupRecord record = backupService.createDatabaseBackup(
                instanceId,
                request.getBackupName(),
                request.getBackupType(),
                request.getDatabaseType(),
                request.getDescription()
        );
        return Result.success(record);
    }

    /**
     * 创建文件备份
     */
    @Operation(summary = "创建文件备份", description = "创建游戏存档/配置文件备份")
    @PostMapping("/files")
    public Result<BackupRecord> createFileBackup(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Valid @RequestBody FileBackupRequest request) {
        BackupRecord record = backupService.createFileBackup(
                instanceId,
                request.getBackupName(),
                request.getBackupType(),
                request.getSourcePath(),
                request.getDescription()
        );
        return Result.success(record);
    }

    /**
     * 获取备份详情
     */
    @Operation(summary = "获取备份详情", description = "获取指定备份的详细信息")
    @GetMapping("/{backupId}")
    public Result<BackupRecord> getDetail(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Parameter(description = "备份ID") @PathVariable Long backupId) {
        BackupRecord record = backupService.getBackupDetail(backupId);
        return Result.success(record);
    }

    /**
     * 获取备份进度
     */
    @Operation(summary = "获取备份进度", description = "获取正在进行的备份进度")
    @GetMapping("/{backupId}/progress")
    public Result<Integer> getProgress(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Parameter(description = "备份ID") @PathVariable Long backupId) {
        Integer progress = backupService.getBackupProgress(backupId);
        return Result.success(progress);
    }

    /**
     * 取消备份
     */
    @Operation(summary = "取消备份", description = "取消正在进行的备份任务")
    @PostMapping("/{backupId}/cancel")
    public Result<Boolean> cancelBackup(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Parameter(description = "备份ID") @PathVariable Long backupId) {
        boolean cancelled = backupService.cancelBackup(backupId);
        return Result.success(cancelled);
    }

    /**
     * 还原备份
     */
    @Operation(summary = "还原备份", description = "从备份还原游戏数据")
    @PostMapping("/{backupId}/restore")
    public Result<Void> restoreBackup(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Parameter(description = "备份ID") @PathVariable Long backupId) {
        backupService.restoreBackup(backupId);
        return Result.success();
    }

    /**
     * 删除备份
     */
    @Operation(summary = "删除备份", description = "删除指定的备份记录和文件")
    @DeleteMapping("/{backupId}")
    public Result<Void> deleteBackup(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Parameter(description = "备份ID") @PathVariable Long backupId) {
        backupService.deleteBackup(backupId);
        return Result.success();
    }

    /**
     * 下载备份文件
     */
    @Operation(summary = "下载备份文件", description = "下载备份文件到本地")
    @GetMapping("/{backupId}/download")
    public ResponseEntity<StreamingResponseBody> downloadBackup(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Parameter(description = "备份ID") @PathVariable Long backupId) {
        // 获取文件信息
        Map<String, Object> fileInfo = backupService.getBackupFileInfo(backupId);
        String filename = (String) fileInfo.get("filename");
        Long size = (Long) fileInfo.get("size");
        String contentType = (String) fileInfo.get("contentType");

        // 构建响应
        StreamingResponseBody responseBody = outputStream -> {
            try (InputStream inputStream = backupService.downloadBackup(backupId)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodeFilename(filename) + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(size)
                .body(responseBody);
    }

    /**
     * 验证备份文件
     */
    @Operation(summary = "验证备份", description = "验证备份文件的完整性")
    @PostMapping("/{backupId}/verify")
    public Result<Boolean> verifyBackup(
            @Parameter(description = "实例ID") @PathVariable Long instanceId,
            @Parameter(description = "备份ID") @PathVariable Long backupId) {
        boolean valid = backupService.verifyBackup(backupId);
        return Result.success(valid);
    }

    /**
     * 编码文件名,处理中文等特殊字符
     */
    private String encodeFilename(String filename) {
        if (filename == null) {
            return "backup";
        }
        // 对文件名进行URL编码,保留ASCII字符
        StringBuilder sb = new StringBuilder();
        for (char c : filename.toCharArray()) {
            if (c > 127 || c == ' ' || c == '"' || c == '<' || c == '>' || c == '|' || c == '?' || c == '*') {
                // 对特殊字符进行编码
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append(String.format("%%%02X", b));
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ========== 请求DTO ==========

    /**
     * 数据库备份请求
     */
    @Data
    public static class DatabaseBackupRequest {
        /**
         * 备份名称
         */
        @NotBlank(message = "备份名称不能为空")
        private String backupName;

        /**
         * 备份类型: FULL-全量, INCREMENTAL-增量
         */
        @NotBlank(message = "备份类型不能为空")
        private String backupType;

        /**
         * 数据库类型: MYSQL, POSTGRESQL, SQLITE
         */
        @NotBlank(message = "数据库类型不能为空")
        private String databaseType;

        /**
         * 备份描述
         */
        private String description;
    }

    /**
     * 文件备份请求
     */
    @Data
    public static class FileBackupRequest {
        /**
         * 备份名称
         */
        @NotBlank(message = "备份名称不能为空")
        private String backupName;

        /**
         * 备份类型: FULL-全量, INCREMENTAL-增量
         */
        @NotBlank(message = "备份类型不能为空")
        private String backupType;

        /**
         * 源路径
         */
        private String sourcePath;

        /**
         * 备份描述
         */
        private String description;
    }

}
