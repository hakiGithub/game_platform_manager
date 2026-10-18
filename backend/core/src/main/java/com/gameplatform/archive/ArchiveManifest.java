package com.gameplatform.archive;

import java.util.List;

/**
 * 单插件 ZIP 的只读清单。
 *
 * @param pluginRoot      归档内唯一插件根目录；无根目录包装时为 null
 * @param pluginName      推导的插件名
 * @param fileCount       文件条目数
 * @param totalBytes      解压后总字节数
 * @param relativeFiles   相对插件根目录的文件路径
 * @param hasStructureRoot 根目录下是否存在调用方指定的结构根
 */
public record ArchiveManifest(
        String pluginRoot,
        String pluginName,
        int fileCount,
        long totalBytes,
        List<String> relativeFiles,
        boolean hasStructureRoot) {
}
