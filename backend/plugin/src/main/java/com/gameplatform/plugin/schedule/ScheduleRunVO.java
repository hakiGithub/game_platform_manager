package com.gameplatform.plugin.schedule;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 定时触发记录 VO（ADR-0011）
 *
 * <p>状态机：RUNNING → SUCCEEDED / FAILED / CANCELLED / SKIPPED（终态不可变）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class ScheduleRunVO {

    private String id;

    /**
     * 关联计划ID
     */
    private String scheduleId;

    /**
     * 计划名称（冗余，便于列表展示）
     */
    private String scheduleName;

    /**
     * 触发方式：CRON / MANUAL
     */
    private String triggerType;

    /**
     * 状态：RUNNING / SUCCEEDED / FAILED / CANCELLED / SKIPPED
     */
    private String status;

    /**
     * 本次执行的 payload 快照
     */
    private Map<String, Object> payload;

    /**
     * 输出结果（反序列化后的 JSON 对象）
     */
    private Map<String, Object> result;

    /**
     * SKIPPED 原因 / 失败错误信息
     */
    private String errorMessage;

    /**
     * 进度百分比 0-100
     */
    private Integer progress;

    /**
     * 进度描述
     */
    private String progressMessage;

    /**
     * 开始执行时间
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

    private LocalDateTime createTime;
}
