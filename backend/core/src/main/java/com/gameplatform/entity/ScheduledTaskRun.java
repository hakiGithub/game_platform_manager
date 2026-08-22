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
 * 定时触发记录实体类（ADR-0011）
 *
 * <p>对应表 scheduled_task_run。状态机：
 * RUNNING → SUCCEEDED / FAILED / CANCELLED / SKIPPED（终态不可变）。
 *
 * <p>run 落 payload 快照与计划名称快照——计划被修改/删除后历史仍可读。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@TableName("scheduled_task_run")
public class ScheduledTaskRun implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（雪花ID，应用层生成，即 runId）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 关联 scheduled_task.id
     */
    private String scheduleId;

    /**
     * 计划名称快照（计划删除后仍可读）
     */
    private String scheduleName;

    /**
     * 触发方式: CRON / MANUAL
     */
    private String triggerType;

    /**
     * 状态: RUNNING / SUCCEEDED / FAILED / CANCELLED / SKIPPED
     */
    private String status;

    /**
     * 本次执行的 payload 快照 JSON
     */
    private String payload;

    /**
     * 输出结果 JSON
     */
    private String result;

    /**
     * SKIPPED 原因 / 失败错误信息
     */
    private String errorMessage;

    /**
     * 进度百分比 0-100
     */
    private Integer progress;

    /**
     * 进度描述文本
     */
    private String progressMessage;

    /**
     * 开始执行时间（SKIPPED 为 null）
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间
     */
    private LocalDateTime completedAt;

    /**
     * 耗时毫秒
     */
    private Long durationMs;

    /**
     * 创建时间（MetaObjectHandler 自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间（MetaObjectHandler 自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
