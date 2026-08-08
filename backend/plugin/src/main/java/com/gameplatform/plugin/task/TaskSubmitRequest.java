package com.gameplatform.plugin.task;

import lombok.Builder;
import lombok.Data;

/**
 * 任务提交请求
 *
 * <p>由调用方（插件或主应用）构造，传递给 {@link TaskService#submit}。
 * payload 序列化后上限 64KB（ADR-026）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
public class TaskSubmitRequest {

    /**
     * 任务类型（如 "crawl"、"deploy"）
     */
    private String taskType;

    /**
     * 任务来源（大写）：MAIN / L4D2 / {gameCode}
     */
    private String source;

    /**
     * 作用域类型：INSTANCE / HOST / GLOBAL（默认 GLOBAL）
     */
    @Builder.Default
    private String scopeType = "GLOBAL";

    /**
     * 作用域键（如 instanceId）
     */
    private String scopeKey;

    /**
     * 作用域名称（如实例名，便于前端展示）
     */
    private String scopeName;

    /**
     * 提交者用户名或 SYSTEM（由 TaskService 实现内部根据调用上下文填充）
     */
    private String submitter;

    /**
     * 任务输入参数（JSON 序列化后上限 64KB）
     */
    private java.util.Map<String, Object> payload;
}
