package com.gameplatform.task;

import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.vo.TaskTypeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务处理器注册表（ADR-002）
 *
 * <p>注册 key 格式：{@code {source}:{taskType}}，例如 {@code L4D2:crawl}、{@code MAIN:deploy}。
 *
 * <ul>
 *   <li>主应用 Handler 通过 @Component + @PostConstruct 调用 {@link #register} 注册</li>
 *   <li>插件 Handler 由 PluginSpringContextFactory 在插件加载时扫描 TaskHandlerExtension 注册</li>
 *   <li>插件卸载时调用 {@link #unregisterBySource} 注销该来源所有 Handler</li>
 * </ul>
 *
 * <p>线程安全：内部使用 {@link ConcurrentHashMap}，register/unregister/get 均原子操作。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class TaskHandlerRegistry {

    /** key = source + ":" + taskType */
    private final Map<String, TaskHandler> handlers = new ConcurrentHashMap<>();

    /** 反向索引：taskId -> source（供 cancelBySource 等场景按 taskId 查找 source） */
    private final Map<String, String> taskSourceIndex = new ConcurrentHashMap<>();

    /**
     * 注册 Handler
     *
     * @param source   任务来源（大写）：MAIN / L4D2 / {gameCode}
     * @param taskType 任务类型
     * @param handler  Handler 实例
     * @throws IllegalStateException 重复注册
     */
    public void register(String source, String taskType, TaskHandler handler) {
        String key = buildKey(source, taskType);
        TaskHandler prev = handlers.putIfAbsent(key, handler);
        if (prev != null) {
            throw new IllegalStateException(
                    "任务处理器已存在: " + key + "，请检查是否重复注册");
        }
        log.info("[TaskCenter] 注册任务处理器: {} -> {}", key, handler.getClass().getSimpleName());
    }

    /**
     * 注销指定来源的所有 Handler（插件卸载时调用）
     *
     * @param source 任务来源
     * @return 注销的 Handler 数量
     */
    public int unregisterBySource(String source) {
        String prefix = source + ":";
        int[] count = {0};
        handlers.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                count[0]++;
                return true;
            }
            return false;
        });
        if (count[0] > 0) {
            log.info("[TaskCenter] 注销来源 [{}] 的 {} 个任务处理器", source, count[0]);
        }
        return count[0];
    }

    /**
     * 获取 Handler
     *
     * @param source   任务来源
     * @param taskType 任务类型
     * @return Handler 实例，未注册返回 null
     */
    public TaskHandler get(String source, String taskType) {
        return handlers.get(buildKey(source, taskType));
    }

    /**
     * 列出所有已注册的任务类型
     */
    public List<TaskTypeVO> listTypes() {
        return handlers.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split(":", 2);
                    return new TaskTypeVO(parts[0], parts[1], e.getValue().getDisplayName());
                })
                .collect(Collectors.toList());
    }

    /**
     * 记录 taskId 与 source 的关联（submit 时调用，cancel/retry 场景使用）
     *
     * @param taskId 任务ID
     * @param source 任务来源
     */
    public void indexTaskSource(String taskId, String source) {
        if (taskId != null && source != null) {
            taskSourceIndex.put(taskId, source);
        }
    }

    /**
     * 查询 taskId 对应的 source
     *
     * @param taskId 任务ID
     * @return source，未找到返回 null
     */
    public String getSourceByTaskId(String taskId) {
        if (taskId == null) {
            return null;
        }
        return taskSourceIndex.get(taskId);
    }

    /**
     * 移除 taskId 索引（任务终态/物理删除时调用）
     *
     * @param taskId 任务ID
     */
    public void removeTaskSourceIndex(String taskId) {
        if (taskId == null) {
            return;
        }
        taskSourceIndex.remove(taskId);
    }

    /**
     * 清空所有索引（崩溃恢复时调用，防止内存泄漏）
     */
    public void clearTaskSourceIndex() {
        taskSourceIndex.clear();
    }

    private String buildKey(String source, String taskType) {
        return source + ":" + taskType;
    }
}
