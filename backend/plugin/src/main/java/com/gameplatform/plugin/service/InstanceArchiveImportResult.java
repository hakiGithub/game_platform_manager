package com.gameplatform.plugin.service;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 单插件归档导入结果（ADR-0031）。 */
@Value
@Builder
public class InstanceArchiveImportResult {

    /** 推导或请求指定的插件名。 */
    String pluginName;

    /** 导入到目标目录的文件数。 */
    int fileCount;

    /** 导入文件总字节数。 */
    long totalBytes;

    /** 导入文件相对路径（相对插件根目录）。 */
    List<String> relativePaths;

    /** 各阶段耗时（毫秒）。 */
    java.util.Map<InstanceArchiveImportService.Phase, Long> phaseDurationsMs;
}
