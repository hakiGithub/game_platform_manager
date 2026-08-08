package com.gameplatform.plugin.task;

import org.pf4j.ExtensionPoint;

import java.util.Map;

/**
 * 任务处理器注册入口（Spring Bean 扩展点）
 *
 * <p>插件实现此接口并标注 {@code @Component}，由 {@code PluginSpringContextFactory}
 * 在加载插件时通过 Spring 子容器扫描此类型 Bean，将返回的 Handler 注册到 {@code TaskHandlerRegistry}。
 *
 * <p>实现要求（ADR-002 / ADR-032）：
 * <ul>
 *   <li>实现类必须标注 {@code @Component}（或 {@code @Configuration}）以被 Spring 扫描</li>
 *   <li>通过构造注入获取插件子容器中的依赖（如 CrawlTaskHandler、MapCenterService）</li>
 *   <li>{@link #getTaskHandlers()} 返回的 Map 在构造时一次性创建并缓存（避免每次调用重新实例化）</li>
 *   <li>Handler 应为无状态对象，状态通过 {@link TaskContext} 传递</li>
 *   <li>任务来源（source）由框架自动填充为插件 gameCode 的大写形式，无需插件指定</li>
 * </ul>
 *
 * <p>示例：
 * <pre>{@code
 * @Component
 * @RequiredArgsConstructor
 * public class L4D2TaskHandlerExtension implements TaskHandlerExtension {
 *     private final CrawlTaskHandler crawlTaskHandler;
 *
 *     private final Map<String, TaskHandler> handlers = Map.of();
 *
 *     public L4D2TaskHandlerExtension(CrawlTaskHandler crawlTaskHandler) {
 *         this.crawlTaskHandler = crawlTaskHandler;
 *         this.handlers = Map.of("crawl", crawlTaskHandler);
 *     }
 *
 *     @Override
 *     public Map<String, TaskHandler> getTaskHandlers() {
 *         return handlers;
 *     }
 * }
 * }</pre>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
public interface TaskHandlerExtension extends ExtensionPoint {

    /**
     * 返回插件提供的任务处理器映射
     *
     * @return key=taskType, value=TaskHandler 实例
     */
    Map<String, TaskHandler> getTaskHandlers();
}
