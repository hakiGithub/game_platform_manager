package com.gameplatform.plugin.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务详情 VO
 *
 * <p>由 {@link TaskService#getTask} / {@link TaskService#listTasks} 返回。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class TaskVO {

    private String id;

    /**
     * 任务类型标识
     */
    private String taskType;

    /**
     * 任务类型显示名称（从 Handler.getDisplayName 获取）
     */
    private String taskTypeName;

    /**
     * 任务来源（大写）
     */
    private String source;

    /**
     * 状态：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED
     */
    private String status;

    /**
     * 提交者用户名或 SYSTEM
     */
    private String submitter;

    /**
     * 作用域类型：INSTANCE / HOST / GLOBAL
     */
    private String scopeType;

    /**
     * 作用域键
     */
    private String scopeKey;

    /**
     * 作用域名称
     */
    private String scopeName;

    /**
     * 输入参数（反序列化后的 JSON 对象）
     */
    private Object payload;

    /**
     * 输出结果（反序列化后的 JSON 对象）
     */
    private Object result;

    /**
     * Handler 生成的结果摘要（列表页展示）
     */
    private String resultSummary;

    /**
     * 进度百分比 0-100
     */
    private Integer progress;

    /**
     * 进度描述
     */
    private String progressMessage;

    /**
     * 失败时的错误信息
     */
    private String errorMessage;

    /**
     * 失败时的堆栈（仅 FAILED 状态返回，详情页可折叠展示）
     */
    private String stackTrace;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数（从 Handler 获取）
     */
    private Integer maxRetryCount;

    /**
     * 是否可重试：retryCount < maxRetryCount && Handler.isRetryable
     */
    private Boolean retryable;

    /**
     * 重试时关联的原任务ID
     */
    private String parentTaskId;

    /**
     * 开始执行时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成/失败/取消时间
     */
    private LocalDateTime completedAt;

    /**
     * 耗时毫秒
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
