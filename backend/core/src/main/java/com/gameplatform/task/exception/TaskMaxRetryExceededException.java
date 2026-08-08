package com.gameplatform.task.exception;

import com.gameplatform.common.result.ResultCode;

/**
 * 超过最大重试次数异常（ADR-012）
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskMaxRetryExceededException extends TaskException {

    private static final long serialVersionUID = 1L;

    public TaskMaxRetryExceededException(String taskId, int maxRetryCount) {
        super(ResultCode.VALIDATE_FAILED,
                "任务已达最大重试次数 " + maxRetryCount + ": " + taskId);
    }
}
