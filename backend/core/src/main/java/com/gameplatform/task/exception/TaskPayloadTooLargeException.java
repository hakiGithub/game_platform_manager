package com.gameplatform.task.exception;

import com.gameplatform.common.result.ResultCode;

/**
 * payload 超过 64KB 上限异常（ADR-026）
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskPayloadTooLargeException extends TaskException {

    private static final long serialVersionUID = 1L;

    public TaskPayloadTooLargeException(int actualBytes) {
        super(ResultCode.VALIDATE_FAILED,
                "任务参数过大: " + actualBytes + " bytes，上限 64KB。大文件请通过文件管理 API 上传后传 URL");
    }
}
