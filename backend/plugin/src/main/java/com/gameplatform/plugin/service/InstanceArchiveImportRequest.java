package com.gameplatform.plugin.service;

import lombok.Builder;
import lombok.Value;

/**
 * 单插件归档导入请求（ADR-0031）。
 *
 * <p>路径字段均为实例相对路径，使用正斜杠；主应用负责解析 Native/Docker 路由。
 */
@Value
@Builder
public class InstanceArchiveImportRequest {

    /**
     * 相对目标目录的提交完成标记。核心在提交动作成功后写入，回滚时删除；
     * 启动恢复依赖它区分“已提交的新目录”和“移动后尚未提交的目录”。
     */
    private String commitMarkerFileName;

    /** 实例 ID。 */
    Long instanceId;

    /** 平台本地 ZIP 路径。 */
    String localArchivePath;

    /** 实例内临时 staging 相对目录，必须位于目标目录同一文件系统。 */
    String stagingRelativePath;

    /** 实例内最终目标相对目录。 */
    String targetRelativePath;

    /** 合法插件根目录名（如内置插件 ID）；为空时由归档单根目录推导。 */
    String expectedPluginName;

    /** 插件根目录下必须存在的结构根，例如 L4D2 的 left4dead2。 */
    String structureRoot;

    /** 解压后条目数上限。 */
    @Builder.Default
    int maxEntries = 10_000;

    /** 解压后总字节数上限。 */
    @Builder.Default
    long maxExtractBytes = 4L * 1024 * 1024 * 1024;

    /** 必须存在的关键文件相对路径（相对插件根目录）。 */
    @Builder.Default
    String[] requiredFiles = new String[0];

    /**
     * 需要校验非空且可读的关键文件扩展名，不含点号。
     *
     * <p>由调用方按游戏插件语义提供；主应用不硬编码具体游戏或插件框架的扩展名。
     */
    @Builder.Default
    String[] criticalFileExtensions = new String[0];

    /**
     * 新目录落位后执行的提交动作，例如写入 plugin.yaml。动作失败时主应用恢复旧目录。
     */
    InstanceArchiveImportCommitAction commitAction;
}
