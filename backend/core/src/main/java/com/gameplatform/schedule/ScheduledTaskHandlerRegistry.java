package com.gameplatform.schedule;

import com.gameplatform.plugin.schedule.ScheduledTaskHandler;
import com.gameplatform.vo.TaskTypeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 定时任务处理器注册表（ADR-0011 D3）
 *
 * <p>注册 key 格式：{@code {source}:{key}}，例如 {@code L4D2:mapCrawl}、{@code MAIN:backup}。
 *
 * <ul>
 *   <li>主应用 Handler 通过 core 侧 @Component 注册（MAIN 来源）</li>
 *   <li>插件 Handler 由 PluginSpringContextFactory 在插件加载时扫描 ScheduledTaskHandler 注册</li>
 *   <li>插件卸载时调用 {@link #unregisterBySource} 注销该来源所有 Handler</li>
 * </ul>
 *
 * <p>计划创建/声明只存 handlerKey 不校验注册表（插件加载顺序不确定）；
 * 触发时经 {@link #get} 解析，未注册返回 null 由引擎记 FAILED run。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class ScheduledTaskHandlerRegistry {

    /** key = source + ":" + handlerKey */
    private final Map<String, ScheduledTaskHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 注册 Handler
     *
     * @param source  来源（大写）：MAIN / {gameCode}
     * @param key     处理器 key
     * @param handler Handler 实例
     * @throws IllegalStateException 重复注册
     */
    public void register(String source, String key, ScheduledTaskHandler handler) {
        String registryKey = buildKey(source, key);
        ScheduledTaskHandler prev = handlers.putIfAbsent(registryKey, handler);
        if (prev != null) {
            throw new IllegalStateException(
                    "定时任务处理器已存在: " + registryKey + "，请检查是否重复注册");
        }
        log.info("[Schedule] 注册定时任务处理器: {} -> {}", registryKey, handler.getClass().getSimpleName());
    }

    /**
     * 注销指定来源的所有 Handler（插件停用/卸载时调用）
     *
     * @param source 来源
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
            log.info("[Schedule] 注销来源 [{}] 的 {} 个定时任务处理器", source, count[0]);
        }
        return count[0];
    }

    /**
     * 获取 Handler
     *
     * @param source     来源
     * @param handlerKey 处理器 key
     * @return Handler 实例，未注册返回 null
     */
    public ScheduledTaskHandler get(String source, String handlerKey) {
        return handlers.get(buildKey(source, handlerKey));
    }

    /**
     * 列出所有已注册的定时任务处理器（前端新建计划时选择用）
     */
    public List<TaskTypeVO> listHandlers() {
        return handlers.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split(":", 2);
                    return new TaskTypeVO(parts[0], parts[1], e.getValue().getDisplayName());
                })
                .sorted((a, b) -> {
                    int c = a.getSource().compareTo(b.getSource());
                    return c != 0 ? c : a.getTaskType().compareTo(b.getTaskType());
                })
                .collect(Collectors.toList());
    }

    /**
     * 列出指定来源的处理器 key 集合（插件声明 upsert 时校验用，仅记 warn 不阻断）
     */
    public List<String> listKeysBySource(String source) {
        String prefix = source + ":";
        return handlers.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toList());
    }

    private String buildKey(String source, String handlerKey) {
        return source + ":" + handlerKey;
    }
}
