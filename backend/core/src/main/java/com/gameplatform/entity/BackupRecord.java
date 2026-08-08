package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 备份记录实体类
 * 对应表: backup_record
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("backup_record")
public class BackupRecord extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 关联实例ID
     */
    private Long instanceId;

    /**
     * 备份名称
     */
    private String backupName;

    /**
     * 备份类型: FULL-全量, INCREMENTAL-增量
     */
    private String backupType;

    /**
     * 目标类型: DATABASE-数据库, FILES-文件
     */
    private String targetType;

    /**
     * 数据库类型: MYSQL, POSTGRESQL, SQLITE (仅数据库备份时有效)
     */
    private String databaseType;

    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 备份文件路径
     */
    private String filePath;

    /**
     * 备份文件MD5校验值
     */
    private String fileMd5;

    /**
     * 备份描述
     */
    private String description;

    /**
     * 状态: 0-备份中, 1-成功, 2-失败
     */
    private Integer status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 备份时间
     */
    private LocalDateTime backupTime;

    /**
     * 完成时间
     */
    private LocalDateTime completeTime;

    /**
     * 备份进度(0-100)
     */
    private Integer progress;

    /**
     * 源路径(文件备份时的源目录)
     */
    private String sourcePath;

    /**
     * 重试次数
     */
    private Integer retryCount;

    // ========== 常量定义 ==========

    /**
     * 备份类型: 全量备份
     */
    public static final String BACKUP_TYPE_FULL = "FULL";

    /**
     * 备份类型: 增量备份
     */
    public static final String BACKUP_TYPE_INCREMENTAL = "INCREMENTAL";

    /**
     * 目标类型: 数据库
     */
    public static final String TARGET_TYPE_DATABASE = "DATABASE";

    /**
     * 目标类型: 文件
     */
    public static final String TARGET_TYPE_FILES = "FILES";

    /**
     * 数据库类型: MySQL
     */
    public static final String DATABASE_TYPE_MYSQL = "MYSQL";

    /**
     * 数据库类型: PostgreSQL
     */
    public static final String DATABASE_TYPE_POSTGRESQL = "POSTGRESQL";

    /**
     * 数据库类型: SQLite
     */
    public static final String DATABASE_TYPE_SQLITE = "SQLITE";

    /**
     * 状态: 备份中
     */
    public static final Integer STATUS_BACKING_UP = 0;

    /**
     * 状态: 成功
     */
    public static final Integer STATUS_SUCCESS = 1;

    /**
     * 状态: 失败
     */
    public static final Integer STATUS_FAILED = 2;

}
