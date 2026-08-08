package com.gameplatform.task.exception;

import com.gameplatform.common.result.ResultCode;

/**
 * 任务类型未找到/未注册异常
 *
 * <p>抛出场景：{@code TaskService.submit} 时找不到对应 (source, taskType) 的 Handler。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskTypeNotFoundException extends TaskException {

    private static final long serialVersionUID = 1L;

    public TaskTypeNotFoundException(String source, String taskType) {
        super(ResultCode.NOT_FOUND,
                "未找到任务处理器: source=" + source + ", taskType=" + taskType);
    }
}
