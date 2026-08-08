package com.gameplatform.plugin.task;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务查询条件
 *
 * <p>用于 {@link TaskService#listTasks} 的分页查询过滤。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
public class TaskQuery {

    /**
     * 任务来源过滤
     */
    private String source;

    /**
     * 任务类型过滤
     */
    private String taskType;

    /**
     * 任务状态过滤
     */
    private String status;

    /**
     * 作用域键过滤
     */
    private String scopeKey;

    /**
     * 提交者过滤
     */
    private String submitter;

    /**
     * 创建时间起始（含）
     */
    private LocalDateTime startTime;

    /**
     * 创建时间结束（含）
     */
    private LocalDateTime endTime;

    /**
     * 关键字搜索（匹配 taskType/scopeName/errorMessage）
     */
    private String keyword;

    /**
     * 页码（默认 1）
     */
    @Builder.Default
    private Integer page = 1;

    /**
     * 每页大小（默认 20）
     */
    @Builder.Default
    private Integer size = 20;
}
