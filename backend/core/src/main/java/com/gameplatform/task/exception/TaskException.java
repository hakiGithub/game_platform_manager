package com.gameplatform.task.exception;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.ResultCode;

/**
 * 任务中心异常基类
 *
 * <p>所有任务中心相关异常均继承此类，统一由 GlobalExceptionHandler 处理为业务异常。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public TaskException(String message) {
        super(message);
    }

    public TaskException(ResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public TaskException(Integer code, String message) {
        super(code, message);
    }
}
