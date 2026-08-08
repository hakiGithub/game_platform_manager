package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.BackupRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 备份记录Mapper接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface BackupRecordMapper extends BaseMapper<BackupRecord> {

    /**
     * 根据实例ID查询备份列表
     *
     * @param instanceId 实例ID
     * @return 备份记录列表
     */
    @Select("SELECT * FROM backup_record WHERE instance_id = #{instanceId} AND is_deleted = 0 ORDER BY backup_time DESC")
    List<BackupRecord> selectByInstanceId(@Param("instanceId") Long instanceId);

    /**
     * 根据实例ID和目标类型查询备份列表
     *
     * @param instanceId  实例ID
     * @param targetType  目标类型
     * @return 备份记录列表
     */
    @Select("SELECT * FROM backup_record WHERE instance_id = #{instanceId} AND target_type = #{targetType} AND is_deleted = 0 ORDER BY backup_time DESC")
    List<BackupRecord> selectByInstanceIdAndTargetType(@Param("instanceId") Long instanceId, @Param("targetType") String targetType);

    /**
     * 查询成功的备份列表
     *
     * @param instanceId 实例ID
     * @return 成功的备份记录列表
     */
    @Select("SELECT * FROM backup_record WHERE instance_id = #{instanceId} AND status = 1 AND is_deleted = 0 ORDER BY backup_time DESC")
    List<BackupRecord> selectSuccessfulBackups(@Param("instanceId") Long instanceId);

    /**
     * 查询过期的备份记录
     *
     * @param expireTime 过期时间
     * @return 过期备份记录列表
     */
    @Select("SELECT * FROM backup_record WHERE backup_time < #{expireTime} AND is_deleted = 0")
    List<BackupRecord> selectExpiredBackups(@Param("expireTime") LocalDateTime expireTime);

    /**
     * 查询实例的备份数量
     *
     * @param instanceId 实例ID
     * @return 备份数量
     */
    @Select("SELECT COUNT(*) FROM backup_record WHERE instance_id = #{instanceId} AND is_deleted = 0")
    Long countByInstanceId(@Param("instanceId") Long instanceId);

    /**
     * 更新备份状态
     *
     * @param backupId    备份ID
     * @param status      状态
     * @param errorMessage 错误信息
     * @return 影响行数
     */
    @Update("UPDATE backup_record SET status = #{status}, error_message = #{errorMessage}, update_time = datetime('now', 'localtime') WHERE id = #{backupId}")
    int updateStatus(@Param("backupId") Long backupId, @Param("status") Integer status, @Param("errorMessage") String errorMessage);

    /**
     * 更新备份进度
     *
     * @param backupId 备份ID
     * @param progress 进度(0-100)
     * @return 影响行数
     */
    @Update("UPDATE backup_record SET progress = #{progress}, update_time = datetime('now', 'localtime') WHERE id = #{backupId}")
    int updateProgress(@Param("backupId") Long backupId, @Param("progress") Integer progress);

    /**
     * 更新备份完成信息
     *
     * @param backupId    备份ID
     * @param status      状态
     * @param fileSize    文件大小
     * @param fileMd5     文件MD5
     * @param errorMessage 错误信息
     * @return 影响行数
     */
    @Update("UPDATE backup_record SET status = #{status}, file_size = #{fileSize}, file_md5 = #{fileMd5}, " +
            "error_message = #{errorMessage}, complete_time = datetime('now', 'localtime'), " +
            "update_time = datetime('now', 'localtime') WHERE id = #{backupId}")
    int updateCompleteInfo(@Param("backupId") Long backupId, @Param("status") Integer status,
                           @Param("fileSize") Long fileSize, @Param("fileMd5") String fileMd5,
                           @Param("errorMessage") String errorMessage);

    /**
     * 增加重试次数
     *
     * @param backupId 备份ID
     * @return 影响行数
     */
    @Update("UPDATE backup_record SET retry_count = retry_count + 1, update_time = datetime('now', 'localtime') WHERE id = #{backupId}")
    int incrementRetryCount(@Param("backupId") Long backupId);

    /**
     * 查询所有备份中的记录
     *
     * @return 备份中的记录列表
     */
    @Select("SELECT * FROM backup_record WHERE status = 0 AND is_deleted = 0")
    List<BackupRecord> selectBackingUpRecords();

}
