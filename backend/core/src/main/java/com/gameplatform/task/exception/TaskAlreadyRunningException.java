package com.gameplatform.task.exception;

import com.gameplatform.common.result.ResultCode;

/**
 * 同 mutexKey 任务已在运行异常
 *
 * <p>抛出场景：{@code TaskService.submit} 时内存互斥检查失败（ADR-018）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskAlreadyRunningException extends TaskException {

    private static final long serialVersionUID = 1L;

    public TaskAlreadyRunningException(String mutexKey) {
        super(ResultCode.CONFLICT, "相同任务正在执行中，请等待完成或取消后重试: " + mutexKey);
    }
}
