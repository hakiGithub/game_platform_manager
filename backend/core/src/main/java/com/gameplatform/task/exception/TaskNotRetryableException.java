package com.gameplatform.task.exception;

import com.gameplatform.common.result.ResultCode;

/**
 * 任务不可重试异常
 *
 * <p>抛出场景：
 * <ul>
 *   <li>任务非终态（FAILED/CANCELLED 之外的状态）</li>
 *   <li>{@code Handler.isRetryable()} 返回 false</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskNotRetryableException extends TaskException {

    private static final long serialVersionUID = 1L;

    public TaskNotRetryableException(String taskId, String reason) {
        super(ResultCode.VALIDATE_FAILED,
                "任务不可重试: " + taskId + "，原因: " + reason);
    }
}
