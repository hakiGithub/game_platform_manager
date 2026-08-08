package com.gameplatform.plugin.task;

import java.util.Collections;
import java.util.Map;

/**
 * 任务执行结果
 *
 * <p>由 {@link TaskHandler#execute} 返回，封装任务执行的输出数据。
 * 序列化后存入 task_record.result 字段（上限 256KB，ADR-026）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskResult {

    private final boolean success;
    private final Map<String, Object> data;
    private final String message;

    private TaskResult(boolean success, Map<String, Object> data, String message) {
        this.success = success;
        this.data = data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
        this.message = message;
    }

    /**
     * 成功结果
     *
     * @param data 输出数据
     * @return TaskResult 实例
     */
    public static TaskResult success(Map<String, Object> data) {
        return new TaskResult(true, data, null);
    }

    /**
     * 成功结果（带消息）
     *
     * @param data    输出数据
     * @param message 成功消息
     * @return TaskResult 实例
     */
    public static TaskResult success(Map<String, Object> data, String message) {
        return new TaskResult(true, data, message);
    }

    /**
     * 成功结果（无数据）
     */
    public static TaskResult success() {
        return new TaskResult(true, Collections.emptyMap(), null);
    }

    /**
     * 失败结果（由 Handler 主动返回失败，区别于抛异常）
     *
     * @param message 失败消息
     * @return TaskResult 实例
     */
    public static TaskResult failure(String message) {
        return new TaskResult(false, Collections.emptyMap(), message);
    }

    /**
     * 失败结果（带数据）
     */
    public static TaskResult failure(String message, Map<String, Object> data) {
        return new TaskResult(false, data, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }
}
