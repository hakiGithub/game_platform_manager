package com.gameplatform.task.exception;

import com.gameplatform.common.result.ResultCode;

/**
 * 任务不存在异常
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskNotFoundException extends TaskException {

    private static final long serialVersionUID = 1L;

    public TaskNotFoundException(String taskId) {
        super(ResultCode.NOT_FOUND, "任务不存在: " + taskId);
    }
}
