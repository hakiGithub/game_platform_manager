package com.gameplatform.plugin.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务日志 VO
 *
 * <p>用于任务详情页日志展示（{@link TaskService#getTaskLogs}）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class TaskLog {

    private String id;
    private String taskId;
    private String level;
    private String message;
    private LocalDateTime createTime;
}
