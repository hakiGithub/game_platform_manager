package com.gameplatform.task.exception;

import com.gameplatform.common.result.ResultCode;

/**
 * 任务不可取消异常
 *
 * <p>抛出场景：任务已进入终态（COMPLETED/FAILED/CANCELLED），无法再次取消。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskNotCancellableException extends TaskException {

    private static final long serialVersionUID = 1L;

    public TaskNotCancellableException(String taskId, String currentStatus) {
        super(ResultCode.VALIDATE_FAILED,
                "任务不可取消: " + taskId + "，当前状态: " + currentStatus);
    }
}
