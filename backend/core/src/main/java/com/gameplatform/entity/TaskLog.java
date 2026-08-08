package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务日志实体类
 *
 * <p>对应表 task_log，与 {@link TaskRecord} 1:N 关联。
 * 单任务最多保留 500 条日志，超出按时间倒序保留最新 500 条。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@TableName("task_log")
public class TaskLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（雪花ID，应用层生成）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 关联 task_record.id
     */
    private String taskId;

    /**
     * 日志级别: INFO / WARN / ERROR
     */
    private String level;

    /**
     * 日志消息
     */
    private String message;

    /**
     * 创建时间（应用层填充，不依赖数据库默认值）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    // ========== 级别常量 ==========

    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";
}
