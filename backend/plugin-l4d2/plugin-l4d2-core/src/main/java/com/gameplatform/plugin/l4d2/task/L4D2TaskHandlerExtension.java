package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskHandlerExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L4D2 插件任务处理器注册入口。
 *
 * <p>由 {@code PluginSpringContextFactory} 在加载插件时通过 Spring 子容器扫描此 Bean，
 * 将返回的 Handler 注册到 {@code TaskHandlerRegistry}。
 *
 * <p>任务来源（source）由框架自动填充为插件 gameCode 的大写形式（L4D2），
 * Handler 只需关注 taskType 与执行逻辑。
 *
 * <p>已注册的处理器：
 * <ul>
 *   <li>{@code crawl} → {@link CrawlTaskHandler}：地图爬取任务</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class L4D2TaskHandlerExtension implements TaskHandlerExtension {

    private final Map<String, TaskHandler> handlers;

    public L4D2TaskHandlerExtension(CrawlTaskHandler crawlTaskHandler) {
        this.handlers = Map.of("crawl", crawlTaskHandler);
        log.info("[L4D2] 任务处理器已注册: crawl -> {}", crawlTaskHandler.getClass().getSimpleName());
    }

    @Override
    public Map<String, TaskHandler> getTaskHandlers() {
        return handlers;
    }
}
