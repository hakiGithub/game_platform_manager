package com.gameplatform.plugin.task;

/**
 * 任务提交上下文
 *
 * <p>由 {@link TaskService#submit} 在调用 {@link TaskHandler#onSubmit} 时构建，
 * 提供提交阶段所需的上下文信息。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskSubmitContext {

    private final String taskType;
    private final String source;
    private final String scopeType;
    private final String scopeKey;
    private final String scopeName;
    private final String submitter;
    private final TaskPayload payload;

    public TaskSubmitContext(String taskType, String source, String scopeType,
                             String scopeKey, String scopeName, String submitter,
                             TaskPayload payload) {
        this.taskType = taskType;
        this.source = source;
        this.scopeType = scopeType;
        this.scopeKey = scopeKey;
        this.scopeName = scopeName;
        this.submitter = submitter;
        this.payload = payload;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getSource() {
        return source;
    }

    public String getScopeType() {
        return scopeType;
    }

    public String getScopeKey() {
        return scopeKey;
    }

    public String getScopeName() {
        return scopeName;
    }

    public String getSubmitter() {
        return submitter;
    }

    public TaskPayload getPayload() {
        return payload;
    }
}
