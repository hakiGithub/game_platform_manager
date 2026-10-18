package com.gameplatform.plugin.service;

import lombok.Getter;

/**
 * 归档导入失败（ADR-0031）。
 *
 * <p>消息包含阶段、路径、命令类别和可操作建议，供任务详情直接展示。
 */
@Getter
public class InstanceArchiveImportException extends RuntimeException {

    /** 命令类别仅用于排障，不暴露实际命令或镜像。 */
    public enum CommandCategory {
        LOCAL_ARCHIVE_READ,
        HOST_UPLOAD,
        HOST_EXTRACT,
        CONTAINER_COPY,
        FILESYSTEM_MOVE,
        CLEANUP,
        VALIDATION
    }

    private final InstanceArchiveImportService.Phase phase;
    private final String path;
    private final CommandCategory commandCategory;
    private final Integer exitCode;
    private final String suggestion;
    private final String failureCode;

    public InstanceArchiveImportException(InstanceArchiveImportService.Phase phase,
                                          String path,
                                          CommandCategory commandCategory,
                                          Integer exitCode,
                                          String suggestion,
                                          String detail,
                                          Throwable cause) {
        this(phase, path, commandCategory, exitCode, suggestion, null, detail, cause);
    }

    public InstanceArchiveImportException(InstanceArchiveImportService.Phase phase,
                                          String path,
                                          CommandCategory commandCategory,
                                          Integer exitCode,
                                          String suggestion,
                                          String failureCode,
                                          String detail,
                                          Throwable cause) {
        super(formatMessage(phase, path, commandCategory, exitCode, suggestion,
                failureCode, detail), cause);
        this.phase = phase;
        this.path = path;
        this.commandCategory = commandCategory;
        this.exitCode = exitCode;
        this.suggestion = suggestion;
        this.failureCode = failureCode;
    }

    private static String formatMessage(InstanceArchiveImportService.Phase phase,
                                        String path,
                                        CommandCategory commandCategory,
                                        Integer exitCode,
                                        String suggestion,
                                        String failureCode,
                                        String detail) {
        StringBuilder message = new StringBuilder("归档导入失败");
        message.append("；阶段=").append(phase == null ? "未知" : phase.getLabel() + "(" + phase + ")");
        message.append("；命令类别=").append(commandCategory == null ? "未知" : commandCategory);
        if (failureCode != null && !failureCode.isBlank()) {
            message.append("；原因码=").append(failureCode);
        }
        if (path != null && !path.isBlank()) {
            message.append("；路径=").append(path);
        }
        if (exitCode != null) {
            message.append("；退出码=").append(exitCode);
        }
        if (detail != null && !detail.isBlank()) {
            message.append("；原因=").append(detail);
        }
        if (suggestion != null && !suggestion.isBlank()) {
            message.append("；建议=").append(suggestion);
        }
        return message.toString();
    }
}
