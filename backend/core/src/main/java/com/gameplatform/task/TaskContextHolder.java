package com.gameplatform.task;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中任务上下文持有器
 *
 * <p>维护 taskId -> TaskContextImpl 映射，用于跨组件访问运行中任务的上下文
 * （例如 TaskAdminServiceImpl.cancel 需要设置 TaskContextImpl.cancelled 标志位）。
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link TaskServiceImpl#executeAsync} 在创建 TaskContextImpl 后调用 {@link #register}</li>
 *   <li>executeAsync 的 finally 块调用 {@link #unregister}</li>
 *   <li>应用启动崩溃恢复时调用 {@link #clear}</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class TaskContextHolder {

    private final ConcurrentHashMap<String, TaskContextImpl> runningContexts = new ConcurrentHashMap<>();

    /**
     * 注册运行中任务上下文
     */
    public void register(String taskId, TaskContextImpl context) {
        if (taskId != null && context != null) {
            runningContexts.put(taskId, context);
        }
    }

    /**
     * 注销任务上下文（executeAsync finally 块调用）
     */
    public void unregister(String taskId) {
        if (taskId != null) {
            runningContexts.remove(taskId);
        }
    }

    /**
     * 获取运行中任务上下文
     *
     * @param taskId 任务ID
     * @return 上下文，不存在返回 null
     */
    public TaskContextImpl get(String taskId) {
        if (taskId == null) {
            return null;
        }
        return runningContexts.get(taskId);
    }

    /**
     * 清空所有运行中上下文（崩溃恢复时调用）
     */
    public void clear() {
        runningContexts.clear();
    }

    /**
     * 当前运行中任务数量（监控用）
     */
    public int size() {
        return runningContexts.size();
    }
}
