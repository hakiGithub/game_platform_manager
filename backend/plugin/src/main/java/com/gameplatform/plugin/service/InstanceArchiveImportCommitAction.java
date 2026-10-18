package com.gameplatform.plugin.service;

/**
 * 归档原子落位后的提交动作（ADR-0031）。
 *
 * <p>主应用在新目录落位后、删除旧目录备份前调用。动作成功才完成提交；
 * 抛异常时主应用恢复旧目录，避免出现“文件已替换但元数据未写入”的半安装状态。
 */
@FunctionalInterface
public interface InstanceArchiveImportCommitAction {

    void afterAtomicMove(InstanceArchiveImportResult imported) throws Exception;
}
