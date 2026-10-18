package com.gameplatform.plugin.service;

/**
 * 归档导入回调（ADR-0031）。
 *
 * <p>实现方必须在每个阶段边界检查取消；取消后主应用清理 staging，且不落位。
 */
public interface InstanceArchiveImportListener {

    /** 进入阶段时调用，percent 为该阶段起始百分比。 */
    void onPhaseStart(InstanceArchiveImportService.Phase phase, int percent, String message);

    /** 阶段内进度，percent 单调不减。 */
    void onProgress(InstanceArchiveImportService.Phase phase, int percent, String message);

    /** 取消检查。返回 true 时主应用尽快中止并清理。 */
    boolean isCancelled();

    /** 结构化日志；level 为 INFO/WARN。 */
    default void onLog(String level, String message) {
    }
}
