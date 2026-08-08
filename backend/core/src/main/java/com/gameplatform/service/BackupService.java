package com.gameplatform.service;

import com.gameplatform.entity.BackupRecord;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 备份服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface BackupService {

    /**
     * 创建数据库备份
     *
     * @param instanceId  实例ID
     * @param backupName  备份名称
     * @param backupType  备份类型: FULL-全量, INCREMENTAL-增量
     * @param databaseType 数据库类型: MYSQL, POSTGRESQL, SQLITE
     * @param description 备份描述
     * @return 备份记录
     */
    BackupRecord createDatabaseBackup(Long instanceId, String backupName, String backupType,
                                       String databaseType, String description);

    /**
     * 创建文件备份
     *
     * @param instanceId  实例ID
     * @param backupName  备份名称
     * @param backupType  备份类型: FULL-全量, INCREMENTAL-增量
     * @param sourcePath  源路径
     * @param description 备份描述
     * @return 备份记录
     */
    BackupRecord createFileBackup(Long instanceId, String backupName, String backupType,
                                   String sourcePath, String description);

    /**
     * 还原备份
     *
     * @param backupId 备份ID
     */
    void restoreBackup(Long backupId);

    /**
     * 删除备份
     *
     * @param backupId 备份ID
     */
    void deleteBackup(Long backupId);

    /**
     * 获取备份列表
     *
     * @param instanceId 实例ID
     * @return 备份记录列表
     */
    List<BackupRecord> getBackupList(Long instanceId);

    /**
     * 获取备份列表(按目标类型筛选)
     *
     * @param instanceId 实例ID
     * @param targetType 目标类型: DATABASE-数据库, FILES-文件
     * @return 备份记录列表
     */
    List<BackupRecord> getBackupListByTargetType(Long instanceId, String targetType);

    /**
     * 获取备份详情
     *
     * @param backupId 备份ID
     * @return 备份记录
     */
    BackupRecord getBackupDetail(Long backupId);

    /**
     * 下载备份文件
     *
     * @param backupId 备份ID
     * @return 文件输入流
     */
    InputStream downloadBackup(Long backupId);

    /**
     * 获取备份文件信息
     *
     * @param backupId 备份ID
     * @return 文件信息Map, 包含filename, size, contentType
     */
    Map<String, Object> getBackupFileInfo(Long backupId);

    /**
     * 清理过期备份
     *
     * @param retentionDays 保留天数
     * @return 清理的备份数量
     */
    int cleanupExpiredBackups(int retentionDays);

    /**
     * 获取备份进度
     *
     * @param backupId 备份ID
     * @return 进度(0-100)
     */
    Integer getBackupProgress(Long backupId);

    /**
     * 取消备份
     *
     * @param backupId 备份ID
     * @return 是否成功取消
     */
    boolean cancelBackup(Long backupId);

    /**
     * 验证备份文件
     *
     * @param backupId 备份ID
     * @return 验证结果
     */
    boolean verifyBackup(Long backupId);

}
