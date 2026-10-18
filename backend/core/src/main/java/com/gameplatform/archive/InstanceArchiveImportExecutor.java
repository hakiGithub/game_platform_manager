package com.gameplatform.archive;

import com.gameplatform.plugin.service.InstanceArchiveImportException;
import com.gameplatform.plugin.service.InstanceArchiveImportListener;
import com.gameplatform.plugin.service.InstanceArchiveImportRequest;

import java.nio.file.Path;

/**
 * 归档导入执行接缝：便于单元测试锁定 staging、挂载和回滚决策。
 */
public interface InstanceArchiveImportExecutor {

    /**
     * 上传原始归档到主机。
     *
     * @return 主机归档绝对路径
     */
    String uploadArchive(InstanceArchiveImportRequest request, Path localArchive,
                         InstanceArchiveImportListener listener);

    /**
     * 解压到 staging，并返回后续阶段共享的固定路径句柄。
     */
    StagingHandle extractToStaging(InstanceArchiveImportRequest request, String hostArchive,
                                   InstanceArchiveImportListener listener);

    /** 将 staging 导入目标父目录内的 staging 位置。 */
    void importToStaging(InstanceArchiveImportRequest request, StagingHandle staging,
                         InstanceArchiveImportListener listener);

    /** 校验实例侧 staging。 */
    ImportedTree validateStaging(InstanceArchiveImportRequest request, StagingHandle staging,
                                 InstanceArchiveImportListener listener);

    /**
     * 将校验通过的 staging 原子落位。旧目录保留为备份，调用方必须最终
     * {@link #finalizeMove} 或 {@link #rollbackMove}。
     */
    AtomicMoveHandle atomicMove(InstanceArchiveImportRequest request, StagingHandle staging,
                                InstanceArchiveImportListener listener);

    /**
     * 标记新目录已完成提交。启动恢复只把带标记的目标视为已提交。
     */
    void markCommitted(AtomicMoveHandle handle, InstanceArchiveImportListener listener);

    /** 提交成功后删除旧目录备份。 */
    void finalizeMove(AtomicMoveHandle handle, InstanceArchiveImportListener listener);

    /** 提交失败时恢复旧目录；原目标不存在时删除新目标。 */
    void rollbackMove(AtomicMoveHandle handle, InstanceArchiveImportListener listener);

    /**
     * 清理主机临时归档与 staging。
     */
    void cleanup(InstanceArchiveImportRequest request, String hostArchive,
                 InstanceArchiveImportListener listener);

    record ImportedTree(int fileCount, long totalBytes, java.util.List<String> relativePaths) {
    }

    /**
     * 原子落位事务句柄。
     *
     * @param hostId          主机 ID
     * @param containerId     容器 ID；Native 为 null
     * @param target          新目录目标路径
     * @param backup          旧目录备份路径
     * @param hadPrevious     落位前目标是否存在
     * @param commitMarker    提交完成标记文件名；为空表示不启用标记
     */
    record AtomicMoveHandle(long hostId, String containerId, String target,
                            String backup, boolean hadPrevious, String commitMarker) {
    }

    /**
     * 一次导入使用的固定 staging 路径。
     *
     * @param instanceStaging 实例内 staging 路径（Native 为宿主路径，Docker 为容器路径）
     * @param hostStaging     宿主机上的 staging 路径；Docker 非挂载时为临时宿主目录
     * @param hostTarget      落位目标在宿主机上的路径；容器未挂载时为 null
     * @param containerStaging 是否位于容器文件系统内
     * @param commitMarker    提交完成标记文件名
     */
    record StagingHandle(String instanceStaging, String hostStaging,
                         String hostTarget, boolean containerStaging, String commitMarker) {
    }
}
