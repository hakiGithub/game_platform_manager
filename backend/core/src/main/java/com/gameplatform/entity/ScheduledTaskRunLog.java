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
 * 定时触发日志实体类（ADR-0011）
 *
 * <p>对应表 scheduled_task_run_log。与 {@link ScheduledTaskRun} 1:N 关联，
 * 每 run 最多保留 500 条（超出按时间倒序保留最新 500 条）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@TableName("scheduled_task_run_log")
public class ScheduledTaskRunLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（雪花ID，应用层生成）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 关联 scheduled_task_run.id
     */
    private String runId;

    /**
     * 日志级别: INFO / WARN / ERROR
     */
    private String level;

    /**
     * 日志消息
     */
    private String message;

    /**
     * 创建时间（应用层填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
